#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="$ROOT/tools"
VERSION="4.23.0"
ARCHIVE_NAME="lean-${VERSION}-linux.tar.zst"
ARCHIVE_URL="https://github.com/leanprover/lean4/releases/download/v${VERSION}/${ARCHIVE_NAME}"
ARCHIVE_SHA256="ecd028d6f642b61b451c8687aeeb24dd53789fbfdcb7d4adb8f5cf60eb2022ba"
ARCHIVE_PATH="$TOOLS_DIR/$ARCHIVE_NAME"
INSTALL_DIR="$TOOLS_DIR/lean-${VERSION}"
LEAN_BIN="$INSTALL_DIR/bin/lean"
MANIFEST="$TOOLS_DIR/toolchain-manifest.json"

mkdir -p "$TOOLS_DIR"
if [[ ! -f "$ARCHIVE_PATH" ]]; then
  download_tmp="$ARCHIVE_PATH.part"
  rm -f "$download_tmp"
  curl --fail --location --retry 3 --output "$download_tmp" "$ARCHIVE_URL"
  mv "$download_tmp" "$ARCHIVE_PATH"
fi

actual_archive_sha256="$(sha256sum "$ARCHIVE_PATH" | cut -d ' ' -f 1)"
if [[ "$actual_archive_sha256" != "$ARCHIVE_SHA256" ]]; then
  printf 'Archive checksum mismatch: expected %s, got %s\n' "$ARCHIVE_SHA256" "$actual_archive_sha256" >&2
  exit 1
fi

if [[ -x "$LEAN_BIN" ]]; then
  version_output="$("$LEAN_BIN" --version)"
  if [[ "$version_output" != *"version ${VERSION},"* ]]; then
    printf 'Existing toolchain has unexpected version: %s\n' "$version_output" >&2
    exit 1
  fi
else
  extract_tmp="$(mktemp -d "$TOOLS_DIR/.lean-${VERSION}.extract.XXXXXX")"
  cleanup() { rm -rf "$extract_tmp"; }
  trap cleanup EXIT
  tar --zstd -xf "$ARCHIVE_PATH" -C "$extract_tmp"
  extracted="$extract_tmp/lean-${VERSION}-linux"
  if [[ ! -x "$extracted/bin/lean" ]]; then
    printf 'Release archive did not contain expected executable %s/bin/lean\n' "$extracted" >&2
    exit 1
  fi
  if [[ -e "$INSTALL_DIR" ]]; then
    printf 'Install destination exists but is incomplete: %s\n' "$INSTALL_DIR" >&2
    exit 1
  fi
  mv "$extracted" "$INSTALL_DIR"
  trap - EXIT
  cleanup
fi

version_output="$("$LEAN_BIN" --version)"
python3 - "$ROOT" "$ARCHIVE_URL" "$ARCHIVE_PATH" "$ARCHIVE_SHA256" "$INSTALL_DIR" "$version_output" "$MANIFEST" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import sys

root, url, archive, expected_archive_sha, install, version, manifest_path = sys.argv[1:]
archive_path = Path(archive)
install_path = Path(install)
executables = {}
for path in sorted((install_path / "bin").rglob("*")):
    if path.is_file() and os.access(path, os.X_OK):
        rel = path.relative_to(install_path).as_posix()
        executables[rel] = hashlib.sha256(path.read_bytes()).hexdigest()

manifest = {
    "version": "4.23.0",
    "platform": "linux-x86_64",
    "source_url": url,
    "archive": {
        "path": archive_path.relative_to(root).as_posix(),
        "sha256": hashlib.sha256(archive_path.read_bytes()).hexdigest(),
        "expected_sha256": expected_archive_sha,
    },
    "installation_path": install_path.relative_to(root).as_posix(),
    "lean_executable": str((install_path / "bin/lean").resolve()),
    "lean_version_output": version,
    "executable_sha256": executables,
}
tmp = Path(manifest_path + ".tmp")
tmp.write_text(json.dumps(manifest, indent=2) + "\n")
tmp.replace(manifest_path)
PY

printf 'Lean toolchain ready: %s\n' "$LEAN_BIN"
printf '%s\n' "$version_output"
printf 'Archive SHA-256: %s\n' "$actual_archive_sha256"
printf 'Manifest: %s\n' "$MANIFEST"
