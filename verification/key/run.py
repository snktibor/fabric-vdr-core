#!/usr/bin/env python3
"""Prove byte-identical production files and replay saved proofs in a fresh JVM."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[1]
URL = 'https://github.com/KeYProject/key/releases/download/KeY-3.0.0/key-3.0.0-exe.jar'
SHA = '1a398e0070c3514e9b258df7073e87a0fad5faf56f35d2e1d66f8947ef884f88'
SOURCES = ['io/github/snktibor/vdr/logic/RevRegEntryVersion.java',
           'io/github/snktibor/vdr/exception/VdrException.java',
           'io/github/snktibor/vdr/model/RevRegEntryRecord.java']
VERSION = 'io.github.snktibor.vdr.logic.RevRegEntryVersion'
RECORD = 'io.github.snktibor.vdr.model.RevRegEntryRecord'

def contract(cls, method, behavior='normal_behavior', index=0):
    return f'{cls}[{cls}::{method}].JML {behavior} operation contract.{index}'

EXPECTED = {
    contract(VERSION, 'next(long)', 'exceptional_behavior', 0): 'next_MAX_rejected_with_frame_and_error_code',
    contract(VERSION, 'next(long)', 'exceptional_behavior', 1): 'next_negative_rejected_with_frame_and_error_code',
    contract(VERSION, 'next(long)'): 'next_exact_mathematical_increment',
    contract(RECORD, 'RevRegEntryRecord()'): 'record_default_initialization',
    contract(RECORD, 'RevRegEntryRecord(java.lang.String,java.lang.String,long)'): 'record_explicit_initialization',
}
for field in ['AccumValue', 'CreatedAt', 'RevRegDefId', 'UpdatedAt', 'Version']:
    EXPECTED[contract(RECORD, f'get{field}()')] = f'record_get{field}_value_and_frame'
    parameter = 'long' if field == 'Version' else 'java.lang.String'
    EXPECTED[contract(RECORD, f'set{field}({parameter})')] = f'record_set{field}_value_and_frame'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, obj):
    path.write_text(json.dumps(obj, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, default=REPO / 'target/key-production')
    parser.add_argument('--key-jar', type=pathlib.Path)
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    # Never leave a previous success result visible after a failed rerun.
    write_json(root / 'result.json', {'status': 'RUNNING', 'started_utc': started})
    commands = []

    def run(argv, log, timeout=300):
        entry = {'argv': list(map(str, argv)), 'cwd': str(root), 'log': log}
        commands.append(entry)
        with (root / log).open('w') as output:
            process = subprocess.Popen(entry['argv'], cwd=root, stdout=output, stderr=subprocess.STDOUT)
            entry['pid'] = process.pid
            (root / (log + '.pid')).write_text(str(process.pid) + '\n')
            write_json(root / 'commands.json', commands)
            try:
                entry['exit_code'] = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                entry['exit_code'] = 'timeout'
        write_json(root / 'commands.json', commands)
        if entry['exit_code'] != 0:
            raise RuntimeError(f'Failed {log}: {entry["exit_code"]}')
        return (root / log).read_text()

    try:
        for directory in ['tools', 'source', 'inputs', 'classes', 'proofs', 'logs', 'config', 'tmp']:
            (root / directory).mkdir(parents=True, exist_ok=True)
        if (root / 'boot').exists():
            raise RuntimeError('This proof slice requires the default KeY boot library; choose a clean output directory')
        jar = args.key_jar.resolve() if args.key_jar else root / 'tools/key-3.0.0-exe.jar'
        if not jar.exists():
            if args.key_jar:
                raise RuntimeError('Explicit KeY JAR not found')
            with urllib.request.urlopen(URL, timeout=60) as stream:
                jar.write_bytes(stream.read())
        if digest(jar) != SHA:
            raise RuntimeError('KeY SHA-256 mismatch')
        original_inputs = {}
        manifest = []
        adapters = []
        expected_paths = set()
        for relative in SOURCES:
            original = REPO / 'src/main/java' / relative
            copied = root / 'source' / relative
            copied.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original, copied)
            assert original.read_bytes() == copied.read_bytes()
            original_inputs[original] = digest(original)
            expected_paths.add(copied)
            manifest.append({'production': str(original.relative_to(REPO)),
                             'proof_input': str(copied.relative_to(root)),
                             'sha256': digest(original), 'byte_identical': True})
        for original in sorted((HERE / 'stubs').rglob('*.java')):
            copied = root / 'source' / original.relative_to(HERE / 'stubs')
            copied.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original, copied)
            original_inputs[original] = digest(original)
            expected_paths.add(copied)
            adapters.append({'file': str(original.relative_to(REPO)),
                             'proof_input': str(copied.relative_to(root)), 'sha256': digest(copied),
                             'role': 'Annotation-name placeholder only; no invoked methods or assumed operation contracts. Jackson metadata/serialization is outside the proof.'})
        if set((root / 'source').rglob('*.java')) != expected_paths:
            raise RuntimeError('Unexpected source files in output directory; use a clean output directory')
        for original in [HERE / 'KeyProduction.java', HERE / 'ReplayProduction.java', HERE / 'run.py']:
            shutil.copyfile(original, root / 'inputs' / original.name)
            original_inputs[original] = digest(original)
        write_json(root / 'source-manifest.json', {'production': manifest, 'stubs': adapters,
                   'verification_inputs': {str(p.relative_to(REPO)): h for p, h in original_inputs.items()},
                   'boot_override': None})
        for stale in (root / 'proofs').glob('contract-*.proof'):
            stale.unlink()
        version = run(['java', '-version'], 'logs/java-version.log')
        run(['javac', '-cp', jar, '-d', root / 'classes', root / 'inputs/KeyProduction.java',
             root / 'inputs/ReplayProduction.java'], 'logs/compile-drivers.log')
        java = ['java', '-ea', '-Xmx2g', f'-Duser.home={root / "config"}',
                f'-Djava.io.tmpdir={root / "tmp"}', '-Djava.awt.headless=true',
                '-cp', f'{jar}:{root / "classes"}']
        proved = run(java + ['KeyProduction', root, str(len(EXPECTED))], 'logs/prove.log', 600)
        replayed = run(java + ['ReplayProduction', root, str(len(EXPECTED))], 'logs/replay.log', 600)
        names = dict(re.findall(r'^CONTRACT (\d+) (.+)$', proved, re.MULTILINE))
        closed = dict(re.findall(r'RESULT (\d+) CLOSED=true OPEN_GOALS=0 NODES=(\d+)', proved))
        replay_ids = re.findall(r'REPLAY (\d+) CLOSED=true OPEN_GOALS=0 ERRORS=false', replayed)
        ids = list(map(str, range(len(EXPECTED))))
        if (list(names) != ids or set(names.values()) != set(EXPECTED)
                or list(closed) != ids or replay_ids != ids):
            raise RuntimeError('Expected every named contract to close and replay exactly once')
        obligations = []
        for i in ids:
            proof = root / f'proofs/contract-{i}.proof'
            builtins = set(re.findall(r'\(builtin "([^"]+)"', proof.read_text()))
            if builtins - {'One Step Simplification', 'Set Statement'}:
                raise RuntimeError(f'Unreviewed builtin rule in {proof}: {builtins}')
            obligations.append({'id': EXPECTED[names[i]], 'contract_name': names[i], 'proof_id': int(i),
                                'proof': str(proof.relative_to(root)), 'sha256': digest(proof),
                                'status': 'PROVED_AND_REPLAYED', 'nodes': int(closed[i]),
                                'open_goals': 0, 'replay_errors': False, 'builtins': sorted(builtins)})
        for original, old_hash in original_inputs.items():
            if digest(original) != old_hash:
                raise RuntimeError(f'Source changed during verification: {original}')
        for row in manifest + adapters:
            if digest(root / row['proof_input']) != row['sha256']:
                raise RuntimeError('Proof input changed during verification')
        artifacts = [root / 'source-manifest.json', root / 'commands.json',
                     *sorted((root / 'inputs').glob('*')), *sorted((root / 'logs').glob('*.log')),
                     *sorted((root / 'proofs').glob('*.proof'))]
        result = {
            'status': 'PROVED_AND_REPLAYED', 'started_utc': started,
            'finished_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'scope': 'Exact production version helper and record constructors/accessors only. Not the service, shared I/O helper, callers, or protocol.',
            'contracts': len(EXPECTED), 'obligations': obligations, 'java': version.strip(),
            'tool': {'url': URL, 'sha256': SHA, 'version': '3.0.0',
                     'internal_revision': '89522a5a4077c73bd37b2eff21da8c7784598d65'},
            'sources': manifest, 'stubs': adapters, 'assumed_adapter_operation_contracts': [],
            'boot_override': None,
            'settings': ['intRules:javaSemantics', 'runtimeExceptions:allow', 'finalFields:onHeap',
                         'initialisation:disableStaticInitialisation', 'METHOD_EXPAND', 'NON_LIN_ARITH_DEF_OPS'],
            'assumptions': [
                'KeY frontend/calculus and bundled JavaRedux library semantics are trusted.',
                'Well-formed heap, type and declared JML nullability assumptions apply.',
                'Static initialization disabled; the selected production classes have no stateful static initializers.',
                'Runtime exceptions are allowed. JVM resource exhaustion and concurrency are outside this sequential proof.',
                'Throwable constructor body expands against JavaRedux ghost message/cause assignments (Set Statement).',
                'No SMT, operation-contract application, user axioms or self-contract assumptions occur in saved proofs.',
                'Jackson annotation is ignored by KeY; its imported name is supplied by a metadata-only interface stub.'
            ],
            'not_proved': ['RevRegEntryService.applyAccumulatorUpdate and public caller composition: KeY generics frontend blocker; run probe_service.py.',
                           'Service creation/duplicate invariants, ordering, registry writes and events: source audit and unit tests only.',
                           'Fabric MVCC, cryptography, serialization, authentication, live peer behavior, Lean refinement.'],
            'sha256': {str(p.relative_to(root)): digest(p) for p in artifacts}}
        write_json(root / 'result.json', result)
        (root / 'failure.json').unlink(missing_ok=True)
        print(f'PASS: {len(EXPECTED)} named production contracts proved and replayed. {root / "result.json"}')
    except BaseException as error:
        failure = {'status': 'FAILED', 'started_utc': started, 'error': str(error),
                   'commands': commands, 'previous_success_invalidated': True}
        write_json(root / 'result.json', failure)
        write_json(root / 'failure.json', failure)
        raise

if __name__ == '__main__':
    main()
