#!/usr/bin/env python3
"""Prove that a frozen-contract mismatch removes a previous successful result."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
(root / "build").mkdir(exist_ok=True)
copy = Path(tempfile.mkdtemp(prefix="failure-invalidation-", dir=root / "build"))
for pattern in ["*.lean", "*.md", "toolchain-lock.json", "lean-toolchain", ".gitignore"]:
    for source in root.glob(pattern):
        shutil.copy2(source, copy / source.name)
shutil.copytree(root / "scripts", copy / "scripts")
shutil.copytree(root / "frozen", copy / "frozen")
(copy / "CONTRACT.md").write_text((copy / "CONTRACT.md").read_text() + "\nDeliberate test-only mismatch.\n")
for name in ["SUCCESS", "theorem-inventory.json", "THEOREMS.md"]:
    (copy / name).write_text("STALE SUCCESS\n")
(copy / "result.json").write_text('{"status":"passed","success":true}\n')
run = subprocess.run(["bash", "scripts/verify.sh"], cwd=copy, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
result = json.loads((copy / "result.json").read_text())
assert run.returncode != 0, "Corrupt frozen contract was accepted"
assert result["status"] == "failed" and result["success"] is False
assert "Frozen hash mismatch: CONTRACT.md" in result["error"]
assert not any((copy / p).exists() for p in ["SUCCESS", "theorem-inventory.json", "THEOREMS.md"])
report = {"status": "passed", "tested": "Frozen contract mismatch invalidates prior success before tool loading",
          "sandbox": str(copy.relative_to(root)), "runner_exit": run.returncode,
          "failure_result": result, "runner_stdout": run.stdout,
          "test_script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
(root / "logs").mkdir(exist_ok=True)
(root / "logs/failure-invalidation.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps({"status": "passed", "runner_exit": run.returncode, "stale_success_removed": True}))
