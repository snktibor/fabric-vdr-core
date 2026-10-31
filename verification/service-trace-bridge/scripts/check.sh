#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_LEAN_ROOT="${LEAN_TOOLCHAIN_DIR:-$TASK_ROOT/../lean-reconstruction/tools/lean-4.23.0}"
export LEAN_PATH="$TASK_ROOT/build"
export LEAN_SYSROOT="$TASK_LEAN_ROOT"
cd -- "$TASK_ROOT"
exec "$TASK_LEAN_ROOT/bin/lean" --trust=0 -o "build/${1%.lean}.olean" "$1"
