#!/usr/bin/env python3
"""Ensure sealed bridge copies match the canonical proofs in this repository."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sources = json.loads((ROOT / 'frozen/dependency-originals.json').read_text())
for label, expected in sources.items():
    folder, name = label.split('/',1)
    if folder not in ['lean-reconstruction','ledger-refinement'] or '/' in name:
        raise RuntimeError(f'Unexpected dependency path: {label}')
    folder = 'lean' if folder == 'lean-reconstruction' else folder
    for path in [ROOT.parent / folder / name, ROOT / 'deps' / name]:
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise RuntimeError(f'Canonical dependency mismatch: {path}')
print(f'PASS: {len(sources)} sealed dependencies match canonical repository proofs.')
