#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# Invalidate success before Python is loaded, including a syntax/import failure.
rm -f SUCCESS theorem-inventory.json THEOREMS.md
printf '{"status":"running","success":false}\n' > result.json
exec python3 scripts/verify.py
