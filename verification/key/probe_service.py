#!/usr/bin/env python3
"""Reproduce KeY 3.0's rejection of the full, unchanged generic service source."""
import argparse
import datetime
import json
import pathlib
import shutil
import subprocess
from run import HERE, REPO, SHA, digest, write_json

SOURCES = ['logic/RevRegEntryService.java', 'logic/RevRegEntryVersion.java',
           'exception/VdrException.java', 'model/RevRegEntryRecord.java',
           'logic/RevRegEntryGuard.java', 'logic/CheckedRevRegEntryGuard.java',
           'logic/GuardedRevRegEntryUpdate.java', 'ledger/VdrEvents.java']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, default=REPO / 'target/key-service-probe')
    parser.add_argument('--key-jar', type=pathlib.Path, required=True)
    args = parser.parse_args()
    root, jar = args.output.resolve(), args.key_jar.resolve()
    root.mkdir(parents=True, exist_ok=True)
    write_json(root / 'result.json', {'status': 'RUNNING'})
    commands, sources, stubs = [], [], []
    try:
        if digest(jar) != SHA:
            raise RuntimeError('KeY SHA-256 mismatch')
        for d in ['source', 'inputs', 'classes', 'logs', 'config', 'tmp']:
            (root / d).mkdir(parents=True, exist_ok=True)
        if (root / 'boot').exists():
            raise RuntimeError('Probe requires default boot library; use a clean output directory')
        expected = set()
        for rel in SOURCES:
            original = REPO / 'src/main/java/io/github/snktibor/vdr' / rel
            copied = root / 'source/io/github/snktibor/vdr' / rel
            copied.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original, copied)
            expected.add(copied)
            sources.append({'production': str(original.relative_to(REPO)),
                            'proof_input': str(copied.relative_to(root)),
                            'sha256': digest(copied), 'byte_identical': original.read_bytes() == copied.read_bytes()})
        for category in ['stubs', 'service-stubs']:
            for original in sorted((HERE / category).rglob('*.java')):
                copied = root / 'source' / original.relative_to(HERE / category)
                copied.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(original, copied)
                expected.add(copied)
                stubs.append({'file': str(original.relative_to(REPO)),
                              'proof_input': str(copied.relative_to(root)), 'sha256': digest(copied),
                              'role': 'Signature-only frontend probe dependency; no successful proof or adapter-contract claim.'})
        if set((root / 'source').rglob('*.java')) != expected:
            raise RuntimeError('Unexpected source files; use a clean output directory')
        for name in ['KeyProduction.java', 'probe_service.py', 'run.py']:
            shutil.copyfile(HERE / name, root / 'inputs' / name)
        def run(argv, log):
            with (root / log).open('w') as output:
                process = subprocess.Popen(list(map(str, argv)), cwd=root, stdout=output, stderr=subprocess.STDOUT)
                item = {'argv': list(map(str, argv)), 'cwd': str(root), 'pid': process.pid, 'log': log}
                commands.append(item)
                (root / (log + '.pid')).write_text(str(process.pid) + '\n')
                write_json(root / 'commands.json', commands)
                try:
                    item['exit_code'] = process.wait(timeout=120)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                    item['exit_code'] = 'timeout'
                write_json(root / 'commands.json', commands)
                return item['exit_code']
        if run(['javac', '-cp', jar, '-d', root / 'classes', root / 'inputs/KeyProduction.java'], 'logs/compile.log') != 0:
            raise RuntimeError('Probe driver compilation failed')
        code = run(['java', '-ea', '-Xmx2g', f'-Duser.home={root / "config"}',
                    f'-Djava.io.tmpdir={root / "tmp"}', '-Djava.awt.headless=true',
                    '-cp', f'{jar}:{root / "classes"}', 'KeyProduction', root, '16'], 'logs/load-service.log')
        log = (root / 'logs/load-service.log').read_text()
        if code != 1 or 'Type arguments found.' not in log or 'CONTRACTS=' in log:
            raise RuntimeError('Expected generic-source frontend rejection not observed; inspect log')
        for row in sources:
            if digest(REPO / row['production']) != row['sha256']:
                raise RuntimeError('Production source changed during probe')
        files = [root / 'commands.json', *sorted((root / 'inputs').glob('*')), *sorted((root / 'logs').glob('*.log'))]
        write_json(root / 'result.json', {
            'status': 'NOT_PROVED_FRONTEND_REJECTED_GENERICS',
            'finished_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'expected_failure_observed': True, 'process_exit_code': code,
            'diagnostic': 'Type arguments found.', 'boot_override': None,
            'sources': sources, 'stubs': stubs,
            'tool_sha256': SHA, 'commands': commands,
            'sha256': {str(p.relative_to(root)): digest(p) for p in files},
            'claim': 'No shared mutation helper or caller contract was loaded or proved. Production Java files are byte-identical; adapter bodies are absent.'})
        print(f'EXPECTED LIMITATION: unchanged service rejected for generics. {root / "result.json"}')
    except BaseException as error:
        write_json(root / 'result.json', {'status': 'FAILED', 'error': str(error), 'commands': commands})
        raise

if __name__ == '__main__':
    main()
