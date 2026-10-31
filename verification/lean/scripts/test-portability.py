#!/usr/bin/env python3
"""Replay the source archive at a new path, reusing the local pinned tools by symlink."""
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile

root = Path(__file__).resolve().parents[1]
archive = root / "artifacts/lean-reconstruction-source.tar.gz"
manifest = json.loads((root / "artifacts/package-manifest.json").read_text())
assert hashlib.sha256(archive.read_bytes()).hexdigest() == manifest["archive_sha256"]
destination = Path(tempfile.mkdtemp(prefix="relocated-source-", dir=root / "build"))
with tarfile.open(archive, "r:gz") as tar:
    for item in tar.getmembers():
        path = Path(item.name)
        assert path.parts[0] == "lean-reconstruction" and ".." not in path.parts
        assert not item.issym() and not item.islnk()
        assert not any(p in {"tools", "build"} for p in path.parts)
    tar.extractall(destination, filter="data")
project = destination / "lean-reconstruction"
assert not (project / "SUCCESS").exists()
assert not (project / "tools").exists()
(project / "tools").symlink_to(root / "tools", target_is_directory=True)
run = subprocess.run(["bash", "scripts/verify.sh"], cwd=project, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
result = json.loads((project / "result.json").read_text())
assert run.returncode == 0 and result["success"], run.stdout
report = {"status": "passed", "method": "Source-only archive replayed at relocated path with identical pinned local tool assets symlinked; no network or global install",
          "archive_sha256": manifest["archive_sha256"], "relocated_project": str(project.relative_to(root)),
          "counts": result["counts"], "replay_log": str((project / result["replay_log"]).relative_to(root)),
          "relocated_result_sha256": hashlib.sha256((project / "result.json").read_bytes()).hexdigest(),
          "test_script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
(root / "logs/portability.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps({"status": "passed", "source_only_archive_relocated_and_replayed": True, "counts": result["counts"]}, indent=2))
