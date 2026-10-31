#!/usr/bin/env python3
"""Check rejection of mismatching bytes even when Python optimization is enabled."""
import contextlib
import io
import json
from pathlib import Path
from unittest.mock import patch

root = Path(__file__).resolve().parents[1]
checker = root / 'scripts/check-integrated-deps.py'
source = checker.read_text()
results = []
for optimization in [0, 1, 2]:
    code = compile(source, str(checker), 'exec', optimize=optimization)
    namespace = {'__file__':str(checker), '__name__':'__main__'}
    with contextlib.redirect_stdout(io.StringIO()) as success:
        exec(code, namespace.copy())
    if not success.getvalue().startswith('PASS:'):
        raise RuntimeError('Valid canonical dependencies were not accepted')
    with patch.object(Path, 'read_bytes', return_value=b'deliberately mismatching dependency'):
        with contextlib.redirect_stdout(io.StringIO()) as failure:
            try:
                exec(code, namespace.copy())
            except RuntimeError as error:
                if 'Canonical dependency mismatch' not in str(error):
                    raise
            else:
                raise RuntimeError(f'Mismatch accepted with optimization={optimization}')
    if 'PASS:' in failure.getvalue():
        raise RuntimeError('Mismatch printed a successful check')
    results.append({'optimization':optimization, 'valid_accepted':True, 'mismatch_rejected':True})
(root / 'logs').mkdir(exist_ok=True)
(root / 'logs/integration-negative.json').write_text(json.dumps(results,indent=2)+'\n')
print('PASS: canonical mismatch rejected at Python optimization levels 0, 1 and 2.')
