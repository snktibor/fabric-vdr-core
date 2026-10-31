#!/usr/bin/env python3
"""Fresh Lean compilation, statement checks, full theorem/axiom inventory and seals."""
from __future__ import annotations

import datetime as dt
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import traceback

ROOT = Path(__file__).resolve().parents[1]
OLD = Path(os.environ.get("RECONSTRUCTION_DIR", str(ROOT.parent / "lean-reconstruction"))).resolve()
OLD_MODULES = ["Model", "Proofs", "Branches", "SkipModel", "SkipProofs", "Examples", "SkipExamples"]
MODULES = ["LedgerModel", "LedgerSpec", "LedgerSpecCorrection", "LedgerProofs",
           "AtomicModel", "AtomicSpec", "AtomicProofs", "Witnesses", "RefinementAudit"]
BASE_TARGETS = {
    "arithmetic": "ArithmeticSpec", "creation": "CreationSpec",
    "revision_stability": "RevisionStabilitySpec", "invariant": "InvariantSpec",
    "state_simulation": "StateSimulationSpec", "commit_simulation": "CommitSimulationSpec",
    "revocation_simulation": "RevocationSimulationSpec", "reject": "RejectSpec",
    "no_reset": "NoResetSpec", "no_bifurcation": "NoBifurcationSpec",
}
ATOMIC_TARGETS = {
    "atomic_writes": "AtomicWritesSpec", "invariant": "InvariantSpec", "no_reset": "NoResetSpec",
    "state_simulation": "StateSimulationSpec", "commit_simulation": "CommitSimulationSpec",
    "revocation_simulation": "RevocationSimulationSpec", "reject": "RejectSpec",
    "no_bifurcation": "NoBifurcationSpec",
}


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def write_json(path: Path, data: object) -> None:
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(data, indent=2) + "\n")
    temp.replace(path)


def frozen_input(relative: str) -> Path:
    prefix = "../lean-reconstruction/"
    if relative.startswith(prefix):
        rest = Path(relative[len(prefix):])
        require(not rest.is_absolute() and ".." not in rest.parts and bool(rest.parts),
                f"Invalid dependency seal path: {relative}")
        return OLD / rest
    local = Path(relative)
    require(not local.is_absolute() and ".." not in local.parts and bool(local.parts),
            f"Invalid local seal path: {relative}")
    return ROOT / local


def seals() -> dict[str, str]:
    records = {}
    for seal in sorted((ROOT / "frozen").glob("*.sha256")):
        for row in seal.read_text().splitlines():
            expected, relative = row.split(maxsplit=1)
            relative = relative.lstrip("*")
            require(digest(frozen_input(relative)) == expected, f"Frozen hash mismatch: {relative}")
            records[relative] = expected
    require(len(records) >= 19, "Incomplete frozen inputs")
    return records


def source_hashes() -> dict[str, str]:
    files = [*ROOT.glob("*.lean"), *ROOT.glob("*.md"), *ROOT.glob("scripts/*.py"),
             *ROOT.glob("frozen/*"), ROOT / "lean-toolchain", ROOT / ".gitignore"]
    if (ROOT / "source-audit.json").exists():
        files.append(ROOT / "source-audit.json")
    return {str(p.relative_to(ROOT)): digest(p) for p in sorted(files)
            if p.name != "THEOREMS.md"}


def explicit_inventory() -> dict[str, dict]:
    records = {}
    targets = {"LedgerRefinement." + k: "LedgerRefinement." + v for k, v in BASE_TARGETS.items()}
    targets.update({"LedgerRefinement.Atomic." + k: "LedgerRefinement.Atomic." + v
                    for k, v in ATOMIC_TARGETS.items()})
    targets["LedgerRefinement.endorsement"] = "LedgerRefinement.EndorsementCorrectedSpec"
    for module in MODULES:
        namespace = ""
        for line_no, line in enumerate((ROOT / f"{module}.lean").read_text().splitlines(), 1):
            m = re.match(r"^namespace\s+([\w.]+)$", line)
            if m:
                namespace = m.group(1)
            m = re.match(r"^theorem\s+(\w+)", line)
            if not m:
                continue
            name = namespace + "." + m.group(1)
            role = "witness" if module == "Witnesses" else "auxiliary"
            if name in targets:
                role = "frozen_target"
            if name == "LedgerRefinement.frozen_endorsement_refuted":
                role = "frozen_target_refutation"
            records[name] = {"name": name, "file": f"{module}.lean", "line": line_no,
                             "role": role, "spec": targets.get(name)}
    return records


def verify(log, run_id: str) -> dict:
    frozen = seals()
    before = source_hashes()
    lock = json.loads((OLD / "toolchain-lock.json").read_text())
    install = Path(os.environ["LEAN_TOOLCHAIN_DIR"]).resolve() if os.environ.get("LEAN_TOOLCHAIN_DIR") else OLD / lock["installation_path"]
    lean = install / "bin/lean"
    for relative, expected in {**lock["executable_sha256"], **lock["runtime_sha256"]}.items():
        require(digest(install / relative) == expected, f"Toolchain mismatch: {relative}")
    version = subprocess.check_output([str(lean), "--version"], text=True).strip()
    require(version == lock["lean_version_output"], f"Wrong Lean: {version}")
    log.write(version + "\nAll pinned runtime/executable hashes verified.\n")
    all_sources = [(OLD, m) for m in OLD_MODULES] + [(ROOT, m) for m in MODULES]
    for folder, module in all_sources:
        src = (folder / f"{module}.lean").read_text()
        require(not re.search(r"\b(sorry|admit|native_decide|axiom|unsafe|implemented_by|extern)\b|debug\.skipKernelTC", src),
                f"Forbidden proof mechanism in {module}")
        for imported in re.findall(r"^import\s+(\S+)", src, re.M):
            require(imported in {*OLD_MODULES, *MODULES, "Lean", "Std"}, f"Unexpected import {imported}")
    build = Path(tempfile.mkdtemp(prefix="fresh-", dir=ROOT / "build"))
    env = os.environ.copy()
    env["LEAN_PATH"] = str(build)
    env["LEAN_SYSROOT"] = str(install)
    commands = []
    audit_text = ""
    for folder, module in all_sources:
        command = [str(lean), "--trust=0", "-o", str(build / f"{module}.olean"), f"{module}.lean"]
        log.write("COMMAND " + json.dumps(command) + " CWD " + str(folder) + "\n")
        log.flush()
        run = subprocess.run(command, cwd=folder, env=env, text=True,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
        log.write(run.stdout + f"EXIT {run.returncode}\n")
        log.flush()
        commands.append({"module": module, "source": str(folder / f"{module}.lean"),
                         "command": command, "exit_code": run.returncode})
        require(run.returncode == 0, f"Kernel check failed: {module}")
        require("declaration uses" not in run.stdout, f"Placeholder warning in {module}")
        if module == "RefinementAudit":
            audit_text = run.stdout
    audit_path = ROOT / "logs" / f"axioms-{run_id}.log"
    audit_path.write_text(audit_text)
    all_axioms = {}
    for name in re.findall(r"^AUDIT_THEOREM (.+)$", audit_text, re.M):
        match = re.search(re.escape("'" + name + "'") +
                          r" (?:does not depend on any axioms|depends on axioms:\s*\[([^\]]*)\])", audit_text)
        require(match is not None, f"Missing dependency print: {name}")
        deps = sorted(x.strip() for x in (match.group(1) or "").split(",") if x.strip())
        whitelist = {"propext", "Quot.sound"}
        if name == "Reconstruction.rejected_has_prior_committed_writer":
            whitelist.add("Classical.choice")
        require(set(deps) <= whitelist, f"Disallowed dependencies: {name}: {deps}")
        all_axioms[name] = deps
    require(bool(all_axioms), "Empty environment audit")
    inventory = explicit_inventory()
    for name, record in inventory.items():
        require(name in all_axioms, f"Named declaration absent from audit: {name}")
        record.update(axioms=all_axioms[name], status="kernel_checked")
        if record["spec"]:
            require(f"#check ({name} : {record['spec']})" in (ROOT / "RefinementAudit.lean").read_text(),
                    f"Missing exact signature check: {name}")
    require(len([r for r in inventory.values() if r["role"] == "frozen_target"]) == 19,
            "Expected ten original, one corrected and eight atomic targets")
    require("LedgerRefinement.frozen_endorsement_refuted" in inventory, "Missing preserved target refutation")
    require(frozen == seals() and before == source_hashes(), "Input changed during replay")
    counts = {"original_targets_proved": 10, "original_targets_refuted": 1,
              "corrected_targets_proved": 1, "atomic_targets_proved": 8,
              "unchanged_dependency_contracts_rechecked": 14,
              "witness_theorems": sum(x["role"] == "witness" for x in inventory.values()),
              "explicit_new_theorems": len(inventory),
              "new_environment_theorems_audited": sum(n.startswith("LedgerRefinement.") for n in all_axioms),
              "all_environment_theorems_audited": len(all_axioms)}
    write_json(ROOT / "theorem-inventory.json", {"counts": counts,
               "theorems": list(inventory.values()), "all_environment_theorem_axioms": all_axioms})
    lines = ["# Kernel-checked theorem inventory", "",
             "Ten initial targets, corrected endorsement and eight atomic targets are proved. The first endorsement statement is refuted, preserved and explicitly corrected.", "",
             "| Declaration | Role | Dependencies |", "|---|---|---|"]
    for record in inventory.values():
        lines.append(f"| [{record['name']}]({record['file']}#L{record['line']}) | {record['role']} | {', '.join(record['axioms']) or 'none'} |")
    (ROOT / "THEOREMS.md").write_text("\n".join(lines) + "\n")
    return {"success": True, "status": "passed_with_explicit_frozen_statement_correction",
            "scope": "concrete semantic refinement; exact Java/JML/Fabric correspondence remains separate",
            "counts": counts, "frozen_sha256": frozen, "source_sha256": before,
            "reconstruction_directory": str(OLD),
            "toolchain_lock_sha256": digest(OLD / "toolchain-lock.json"), "lean_version": version,
            "trust_level": 0, "commands": commands, "build_directory": str(build.relative_to(ROOT)),
            "axioms_log": str(audit_path.relative_to(ROOT)),
            "evidence_sha256": {str(p.relative_to(ROOT)): digest(p)
                                for p in [ROOT / "theorem-inventory.json", ROOT / "THEOREMS.md", audit_path]}}


def package(result: dict) -> None:
    """Source/evidence only; imported project sources are copied without edits."""
    artifact_dir = ROOT / "artifacts"
    artifact_dir.mkdir(exist_ok=True)
    files = {ROOT / p for p in result["source_sha256"]}
    files.update(ROOT / p for p in ["result.json", "SUCCESS", "theorem-inventory.json", "THEOREMS.md",
                                    result["replay_log"], result["axioms_log"]])
    files.update(frozen_input(relative) for relative in result["frozen_sha256"] if relative.startswith("../"))
    files.add(OLD / "toolchain-lock.json")
    archive = artifact_dir / "ledger-refinement-source.tar.gz"
    manifest = {}
    with tarfile.open(archive, "w:gz") as tar:
        for path in sorted(files):
            if path.is_relative_to(ROOT):
                relative = "ledger-refinement/" + str(path.relative_to(ROOT))
            else:
                relative = "lean-reconstruction/" + str(path.relative_to(OLD))
            manifest[relative] = digest(path)
            tar.add(path, arcname="ledger-refinement-package/" + relative, recursive=False)
    write_json(artifact_dir / "package.json", {"archive": archive.name,
               "sha256": digest(archive), "bytes": archive.stat().st_size,
               "toolchain_included": False, "source_and_evidence_sha256": manifest,
               "replay": "LEAN_TOOLCHAIN_DIR=/absolute/path/to/lean-4.23.0 python3 ledger-refinement/scripts/replay.py"})


def check_package() -> dict:
    rejected_paths = []
    for bad_path in ["../elsewhere/Model.lean", "../lean-reconstruction/../escape", "/tmp/absolute", "../lean-reconstruction//tmp"]:
        try:
            frozen_input(bad_path)
        except RuntimeError:
            rejected_paths.append(bad_path)
        else:
            raise RuntimeError(f"Seal resolver accepted invalid traversal: {bad_path}")
    archive = ROOT / "artifacts/ledger-refinement-source.tar.gz"
    manifest = json.loads((ROOT / "artifacts/package.json").read_text())
    require(digest(archive) == manifest["sha256"], "Package digest mismatch")
    location = Path(tempfile.mkdtemp(prefix="relocated-", dir=ROOT / "build"))
    with tarfile.open(archive, "r:gz") as tar:
        require(all(not m.name.endswith((".olean", ".ilean", ".class", ".jar", ".so"))
                    and "/tools/" not in m.name for m in tar.getmembers()), "Package includes binary/build inputs")
        tar.extractall(location, filter="data")
    unpacked = location / "ledger-refinement-package"
    for relative, expected in manifest["source_and_evidence_sha256"].items():
        require(digest(unpacked / relative) == expected, f"Archive entry mismatch: {relative}")
    lock = json.loads((OLD / "toolchain-lock.json").read_text())
    install = Path(os.environ["LEAN_TOOLCHAIN_DIR"]).resolve() if os.environ.get("LEAN_TOOLCHAIN_DIR") else OLD / lock["installation_path"]
    env = os.environ.copy()
    env["LEAN_TOOLCHAIN_DIR"] = str(install)
    env["RECONSTRUCTION_DIR"] = str(unpacked / "lean-reconstruction")
    child_root = unpacked / "ledger-refinement"
    command = [sys.executable, str(child_root / "scripts/replay.py")]
    run = subprocess.run(command, cwd=location, env=env, text=True, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, timeout=300)
    (ROOT / "artifacts/relocated-replay.log").write_text(run.stdout)
    child_result = json.loads((child_root / "result.json").read_text())
    require(run.returncode == 0 and child_result["success"], "Relocated replay failed")
    require((child_root / "SUCCESS").exists(), "Missing relocated success marker")
    with (child_root / "LedgerModel.lean").open("a") as out:
        out.write("\n-- Deliberate frozen-input corruption for fail-closed check.\n")
    bad = subprocess.run(command, cwd=location, env=env, text=True, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, timeout=60)
    (ROOT / "artifacts/corruption-replay.log").write_text(bad.stdout)
    bad_result = json.loads((child_root / "result.json").read_text())
    require(bad.returncode != 0 and not bad_result["success"] and
            "Frozen hash mismatch" in bad_result.get("error", ""), "Corrupted seal did not fail")
    require(not (child_root / "SUCCESS").exists(), "Failure retained stale success marker")
    evidence = {"status": "passed", "tested_archive_sha256": manifest["sha256"],
                "archive_entries_verified": len(manifest["source_and_evidence_sha256"]),
                "seal_resolver_rejected_paths": rejected_paths,
                "source_only": True, "relocated_root": str(child_root),
                "relocated_replay": child_result, "corrupted_frozen_input": bad_result,
                "stale_success_marker_removed": True,
                "log_sha256": {name: digest(ROOT / "artifacts" / name)
                               for name in ["relocated-replay.log", "corruption-replay.log"]}}
    write_json(ROOT / "artifacts/portability.json", evidence)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-package", action="store_true",
                        help="also replay the source package from a new location and test seal failure")
    args = parser.parse_args()
    for folder in ["build", "logs"]:
        (ROOT / folder).mkdir(exist_ok=True)
    for name in ["SUCCESS", "theorem-inventory.json", "THEOREMS.md"]:
        (ROOT / name).unlink(missing_ok=True)
    (ROOT / "artifacts/portability.json").unlink(missing_ok=True)
    write_json(ROOT / "result.json", {"status": "running", "success": False})
    run_id = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    log_path = ROOT / "logs" / f"replay-{run_id}.log"
    with log_path.open("w") as log:
        try:
            result = verify(log, run_id)
        except BaseException as exc:
            log.write(traceback.format_exc())
            result = {"success": False, "status": "failed", "error": str(exc)}
    result.update(run_id=run_id, replay_log=str(log_path.relative_to(ROOT)),
                  replay_log_sha256=digest(log_path))
    write_json(ROOT / "result.json", result)
    if result["success"]:
        (ROOT / "SUCCESS").write_text(digest(ROOT / "result.json") + "  result.json\n")
        try:
            package(result)
            if args.check_package:
                check_package()
        except BaseException as exc:
            (ROOT / "SUCCESS").unlink(missing_ok=True)
            result.update(success=False, status="failed", error=f"Artifact check failed: {exc}")
            write_json(ROOT / "result.json", result)
    print(json.dumps({k: result[k] for k in ["success", "status", "counts", "error", "replay_log"] if k in result}, indent=2))
    return 0 if result["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
