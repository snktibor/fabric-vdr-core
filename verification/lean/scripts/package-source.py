#!/usr/bin/env python3
"""Package the checked source project and small evidence files, excluding tool/build blobs."""
import hashlib
import json
from pathlib import Path
import tarfile

root = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


result = json.loads((root / "result.json").read_text())
assert result["status"] == "passed" and result["success"], "Run verify.sh successfully first"
assert (root / "SUCCESS").read_text().split()[0] == sha(root / "result.json"), "Invalid success marker"
for relative, expected in result["source_script_document_sha256"].items():
    assert sha(root / relative) == expected, f"Changed after replay: {relative}"
selected = {root / "lean-toolchain", root / ".gitignore"}
for pattern in ["*.lean", "*.md", "*.json", "scripts/*.py", "scripts/*.sh", "frozen/*", "logs/*.log", "logs/*.json"]:
    selected.update(p for p in root.glob(pattern) if p.is_file())
artifacts = root / "artifacts"
artifacts.mkdir(exist_ok=True)
archive = artifacts / "lean-reconstruction-source.tar.gz"
files = {}
with tarfile.open(archive, "w:gz") as tar:
    for path in sorted(selected):
        relative = path.relative_to(root).as_posix()
        files[relative] = sha(path)
        tar.add(path, arcname="lean-reconstruction/" + relative, recursive=False)
manifest = {"kind": "source-and-small-evidence-only", "archive": str(archive.relative_to(root)),
            "archive_sha256": sha(archive), "bytes": archive.stat().st_size, "files_sha256": files,
            "excluded": ["tools/", "build/", "root .olean/.ilean files", "SUCCESS"],
            "verification_result_sha256": sha(root / "result.json")}
(artifacts / "package-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps({k: manifest[k] for k in ["archive", "archive_sha256", "bytes"]}, indent=2))
