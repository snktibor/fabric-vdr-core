#!/usr/bin/env python3
"""Fresh kernel replay and exhaustive reconstruction-theorem axiom audit."""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import traceback

ROOT = Path(__file__).resolve().parents[1]
MODULES = ["Model", "Proofs", "Branches", "SkipModel", "SkipProofs", "Examples", "SkipExamples", "Audit"]
CONTRACTS = {
    "final_mono": ("FinalMonoSpec", "core"),
    "strict_committed_write_growth": ("StrictGrowthSpec", "core"),
    "stale_never_commits": ("StaleNeverCommitsSpec", "core"),
    "tas_spec": ("TasSpec", "core"),
    "window_closed": ("WindowClosedSpec", "core"),
    "check_holds_at_use": ("CheckHoldsSpec", "core"),
    "no_bifurcation": ("NoBifurcationSpec", "core"),
    "rejected_has_prior_committed_writer": ("PriorWriterSpec", "optional_branch"),
    "at_most_one": ("FreshPairSpec", "optional_branch"),
    "skip_state_simulation": ("SkipStateSimulationSpec", "skip_extension"),
    "skip_commit_simulation": ("SkipCommitSimulationSpec", "skip_extension"),
    "skip_revocation_simulation": ("SkipRevocationSimulationSpec", "skip_extension"),
    "skip_no_bifurcation": ("SkipNoBifurcationSpec", "skip_extension"),
    "skip_rejects": ("SkipRejectsSpec", "skip_extension"),
}
CHOICE_REASON = {
    "Reconstruction.rejected_has_prior_committed_writer":
        "Classical.byContradiction extracts existence of a conflicting prior writer from failed universal validation. This optional detectability result is outside the constructive safety chain."
}


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def write_json(path: Path, data: object) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    tmp.replace(path)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def sources() -> list[Path]:
    return sorted({*ROOT.glob("*.lean"), *ROOT.glob("*.md"), *ROOT.glob("*.json"),
                   *ROOT.glob("scripts/*.py"), *ROOT.glob("scripts/*.sh"),
                   *ROOT.glob("frozen/*"), ROOT / "lean-toolchain", ROOT / ".gitignore"}
                  - {ROOT / "result.json", ROOT / "theorem-inventory.json", ROOT / "THEOREMS.md"})


def explicit_theorems() -> dict[str, dict]:
    found = {}
    for module in MODULES:
        stack = []
        for line_no, line in enumerate((ROOT / f"{module}.lean").read_text().splitlines(), 1):
            m = re.match(r"^namespace\s+([\w.]+)\s*$", line)
            if m:
                stack.append(m.group(1))
                continue
            if re.match(r"^end(?:\s+[\w.]+)?\s*$", line):
                if stack:
                    stack.pop()
                continue
            m = re.match(r"^(?:@\[[^\]]*\]\s*)?(?:(private|protected)\s+)?theorem\s+(\w+)", line)
            if not m:
                continue
            require(m.group(1) != "private", "Use public named theorem declarations for the portable inventory")
            name = ".".join([*stack, m.group(2)])
            short = m.group(2)
            if name == "Reconstruction." + short and short in CONTRACTS:
                spec, group = CONTRACTS[short]
                role = "contract"
            else:
                spec, group = None, ("examples" if module in {"Examples", "SkipExamples"} else "helpers")
                role = "witness_or_counterexample" if group == "examples" else "auxiliary"
            found[name] = {"name": name, "file": f"{module}.lean", "line": line_no,
                           "role": role, "group": group, "frozen_spec": spec}
    return found


def verify(log, run_id: str) -> dict:
    before = {str(p.relative_to(ROOT)): digest(p) for p in sources()}
    seals = {}
    for seal_name in ["semantic.sha256", "skip-semantic.sha256"]:
        seal = ROOT / "frozen" / seal_name
        for row in seal.read_text().splitlines():
            expected, relative = row.split(maxsplit=1)
            relative = relative.lstrip("*")
            require(digest(ROOT / relative) == expected, f"Frozen hash mismatch: {relative}")
            seals[relative] = expected
    log.write("Frozen model and contract hashes verified.\n")

    for module in MODULES:
        content = (ROOT / f"{module}.lean").read_text()
        bad = re.search(r"\b(sorry|admit|native_decide|axiom|unsafe|implemented_by|extern)\b|debug\.skipKernelTC", content)
        # Audit contains the diagnostic string 'Custom reconstruction axiom'.
        if module == "Audit":
            content = content.replace('"Custom reconstruction axiom: {n}"', '"Custom reconstruction declaration: {n}"')
            bad = re.search(r"\b(sorry|admit|native_decide|axiom|unsafe|implemented_by|extern)\b|debug\.skipKernelTC", content)
        require(not bad, f"Forbidden proof mechanism in {module}.lean: {bad.group(0) if bad else ''}")
        for imported in re.findall(r"^import\s+(\S+)", content, flags=re.M):
            require(imported in {*MODULES, "Std", "Lean"}, f"Unapproved import: {imported}")
    lock = json.loads((ROOT / "toolchain-lock.json").read_text())
    install = ROOT / lock["installation_path"]
    lean = install / "bin/lean"
    actual_tools = {}
    for relative, expected in {**lock["executable_sha256"], **lock["runtime_sha256"]}.items():
        actual = digest(install / relative)
        require(actual == expected, f"Toolchain file hash mismatch: {relative}")
        actual_tools[relative] = actual
    archive = ROOT / lock["archive"]["path"]
    if archive.exists():
        require(digest(archive) == lock["archive"]["sha256"], "Toolchain archive hash mismatch")
    version = subprocess.check_output([str(lean), "--version"], text=True).strip()
    require(version == lock["lean_version_output"], f"Wrong toolchain: {version}")
    log.write(version + "\n")

    build_parent = ROOT / "build"
    build_parent.mkdir(exist_ok=True)
    build = Path(tempfile.mkdtemp(prefix="replay-", dir=build_parent))
    env = os.environ.copy()
    env["LEAN_PATH"] = str(build)
    env["LEAN_SYSROOT"] = str(install)
    commands = []
    audit_text = ""
    for module in MODULES:
        command = [str(lean), "-o", str(build / f"{module}.olean"), f"{module}.lean"]
        log.write("COMMAND " + json.dumps([str(Path(command[0]).relative_to(ROOT)), "-o",
                                             str(Path(command[2]).relative_to(ROOT)), command[3]]) + "\n")
        log.flush()
        run = subprocess.run(command, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        log.write(run.stdout)
        log.write(f"EXIT {run.returncode}\n")
        log.flush()
        commands.append({"module": module, "exit_code": run.returncode})
        require(run.returncode == 0, f"Kernel compilation failed: {module}.lean")
        require("warning: declaration uses" not in run.stdout, f"Placeholder warning: {module}")
        if module == "Audit":
            audit_text = run.stdout
    axioms_log = ROOT / "logs" / f"axioms-{run_id}.log"
    axioms_log.write_text(audit_text)
    names = re.findall(r"^AUDIT_THEOREM (.+)$", audit_text, flags=re.M)
    audited = {}
    for name in names:
        pattern = re.escape("'" + name + "'") + r" (?:does not depend on any axioms|depends on axioms:\s*\[([^\]]*)\])"
        match = re.search(pattern, audit_text)
        require(match is not None, f"Missing axiom output: {name}")
        dependencies = sorted(x.strip() for x in (match.group(1) or "").split(",") if x.strip())
        allowed = {"propext", "Quot.sound"}
        if name in CHOICE_REASON:
            allowed.add("Classical.choice")
        require(set(dependencies) <= allowed, f"Disallowed axioms for {name}: {dependencies}")
        audited[name] = dependencies
    require(bool(audited), "Empty theorem audit")
    explicit = explicit_theorems()
    for name, record in explicit.items():
        require(name in audited, f"Exported theorem missing from environment audit: {name}")
        record.update(status="newly_proved", axioms=audited[name])
        if "Classical.choice" in audited[name]:
            record["choice_explanation"] = CHOICE_REASON[name]
    for target, (spec, _) in CONTRACTS.items():
        require("Reconstruction." + target in explicit, f"Missing frozen target: {target}")
        checked = f"#check (Reconstruction.{target} : Reconstruction.{spec})"
        require(checked in (ROOT / "Audit.lean").read_text(), f"Missing direct contract check: {target}")
    after = {str(p.relative_to(ROOT)): digest(p) for p in sources()}
    require(before == after, "Sources changed during verification")
    counts = {"named_contracts": sum(x["role"] == "contract" for x in explicit.values()),
              "core_contracts": 7, "optional_branch_contracts": 2, "skip_extension_contracts": 5,
              "auxiliary_theorems": sum(x["role"] == "auxiliary" for x in explicit.values()),
              "witness_or_counterexample_theorems": sum(x["role"] == "witness_or_counterexample" for x in explicit.values()),
              "explicit_named_theorems_total": len(explicit),
              "environment_theorem_constants_audited_including_generated": len(audited)}
    inventory = {"status": "passed", "counts": counts, "theorems": list(explicit.values()),
                 "all_environment_theorem_axioms": audited}
    write_json(ROOT / "theorem-inventory.json", inventory)
    rows = ["# Checked theorem inventory", "", "Independent reconstruction, all listed statements newly proved.", "",
            json.dumps(counts, indent=2), "", "| Declaration | Role | Status | Axioms |", "|---|---|---|---|"]
    for rec in explicit.values():
        rows.append(f"| [{rec['name']}]({rec['file']}#L{rec['line']}) | {rec['role']} | newly proved | {', '.join(rec['axioms']) or 'none'} |")
    rows += ["", "Only the optional prior-writer theorem uses Classical.choice, for classical existence extraction.",
             "The original development and its stated count of twenty main theorems are not reproduced."]
    (ROOT / "THEOREMS.md").write_text("\n".join(rows) + "\n")
    return {"status": "passed", "success": True, "provenance": "independent reconstruction",
            "implementation_model_correspondence": "OPEN", "original_source_comparison": "OPEN",
            "counts": counts, "frozen_sha256": seals, "source_script_document_sha256": before,
            "toolchain_lock_sha256": digest(ROOT / "toolchain-lock.json"),
            "toolchain_files_verified": len(actual_tools), "toolchain_archive_sha256": lock["archive"]["sha256"],
            "lean_version": version, "commands": commands,
            "build_directory": str(build.relative_to(ROOT)), "axioms_log": str(axioms_log.relative_to(ROOT)),
            "generated_evidence_sha256": {p: digest(ROOT / p) for p in ["theorem-inventory.json", "THEOREMS.md", str(axioms_log.relative_to(ROOT))]}}


def main() -> int:
    os.chdir(ROOT)
    for name in ["SUCCESS", "theorem-inventory.json", "THEOREMS.md"]:
        (ROOT / name).unlink(missing_ok=True)
    write_json(ROOT / "result.json", {"status": "running", "success": False})
    (ROOT / "logs").mkdir(exist_ok=True)
    run_id = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    log_path = ROOT / "logs" / f"replay-{run_id}.log"
    with log_path.open("w") as log:
        try:
            result = verify(log, run_id)
        except BaseException as exc:
            log.write(traceback.format_exc())
            result = {"status": "failed", "success": False, "error": str(exc)}
            for name in ["SUCCESS", "theorem-inventory.json", "THEOREMS.md"]:
                (ROOT / name).unlink(missing_ok=True)
        result["run_id"] = run_id
        result["replay_log"] = str(log_path.relative_to(ROOT))
    result["replay_log_sha256"] = digest(log_path)
    write_json(ROOT / "result.json", result)
    if result["success"]:
        (ROOT / "SUCCESS").write_text(digest(ROOT / "result.json") + "  result.json\n")
    print(json.dumps({k: result[k] for k in ["status", "success", "counts", "error", "replay_log"] if k in result}, indent=2))
    return 0 if result["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
