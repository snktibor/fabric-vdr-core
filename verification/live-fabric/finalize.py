#!/usr/bin/env python3
"""Aggregate completed local evidence after cleanup; failure never emits validated success."""
from evidence import *
import zipfile

def summary(file):
    data=json.loads(file.read_text())
    scenarios=[x for f in data for x in f.get('elements',[]) if x.get('type')=='scenario']
    states=collections.Counter('passed' if {s['result']['status'] for s in x['steps']}=={'passed'} else 'failed' for x in scenarios)
    return {'scenarios':len(scenarios),**states}

def aggregate():
    focused=summary(ROOT/'focused-cucumber.json');full=summary(ROOT/'full-cucumber.json')
    count=len(json.loads((ROOT/'scenario-inventory.json').read_text()))
    assert focused=={'scenarios':6,'passed':6},focused
    assert full=={'scenarios':count,'passed':count},full
    focused_races=check(ROOT/'logs/focused-e2e.log','focused')
    check(ROOT/'logs/full-e2e.log','full')
    for name in ['focused','full']:
        assert json.loads((ROOT/f'logs/{name}-e2e.json').read_text())['exitcode']==0
    survival=json.loads((ROOT/'unrelated-survival.json').read_text())
    assert survival['initial_ids_preserved'] and survival['initial_states_unchanged']
    assert hashes(REPO)==hashes(ROOT/'project')==json.loads((ROOT/'source-hashes.json').read_text())
    after=max((ROOT/'logs').glob('git-status-after*.log'),key=lambda p:p.stat().st_mtime)
    assert (ROOT/'logs/git-status-before.log').read_bytes()==after.read_bytes()
    classes()
    correspondence=json.loads((ROOT/'class-correspondence.json').read_text())
    manifest=json.loads((ROOT/'deployment-manifest.json').read_text())
    jar=hashlib.sha256((ROOT/'artifact/chaincode.jar').read_bytes()).hexdigest()
    assert jar==manifest['jar_sha256']==(ROOT/'logs/cc-jar-sha.log').read_text().split()[0]
    for name in ['peer0','peer1','orderer']:
        assert '2.5.15' in (ROOT/f'logs/runtime-version-{name}.log').read_text()
    ledger=json.loads((ROOT/'ledger-race-evidence.json').read_text());assert len(ledger)==8
    blocks=sorted({int(x['block']) for x in ledger})
    for number in blocks:
        raw=(ROOT/f'blocks/{number}.pb').read_bytes()
        logs=sorted((ROOT/'logs').glob(f'peer1-block-{number}*.log'))
        assert logs
        assert any(bytes.fromhex(line.strip())==raw for log in logs for line in log.read_text().splitlines() if re.fullmatch('[0-9a-fA-F]{100,}',line.strip()))
    for v in ledger:
        obj=json.loads((ROOT/f"blocks/{v['block']}.json").read_text())
        ids=[x['payload']['header']['channel_header']['tx_id'] for x in obj['data']['data']]
        assert base64.b64decode(obj['metadata']['metadata'][2])[ids.index(v['txId'])]=={'VALID':0,'MVCC_READ_CONFLICT':11}[v['validationCode']]
        assert hashlib.sha256((ROOT/f"blocks/{v['block']}.pb").read_bytes()).hexdigest()==v['block_sha256']
    assert not (ROOT/'organizations').exists() and not (ROOT/'data').exists()
    assert json.loads((ROOT/'sanitization.json').read_text())['private_keys_retained'] is False
    # All recorded resources, including any sanitizer, must be absent.
    owned=json.loads((ROOT/'owned.json').read_text())
    for id in owned['containers']+owned['networks']+owned.get('images',[]):
        kind='image' if id.startswith('sha256:') else ('network' if id in owned['networks'] else 'container')
        inspect=['docker',kind,'inspect',id]
        p=subprocess.run(inspect,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        assert p.returncode and any(x in p.stderr.lower() for x in ['no such','not found']),id
    versions={n:(ROOT/f'logs/runtime-version-{n}.log').read_text().strip() for n in ['peer0','peer1','orderer']}
    results={'validated':True,'focused':focused,'full':full,'source_unchanged':True,'production_classes_byte_identical_to_jar':correspondence['production_class_count'],'git_status_unchanged':True,'initial_container_ids_and_states_unchanged':True,'runtime_versions':versions,'jar_sha256':jar,'source_tree_manifest_sha256':manifest['source_tree_sha256'],'concurrency_peer_blocks':blocks,'race_validation_codes':{k:[v['validationCode'] for v in focused_races[k]] for k in ['A1','A2','B1u','B1']},'peer_block_copies_identical':True,'cleanup':'all recorded containers, network and custom chaincode image absent; downloaded official image cache retained','generated_crypto_absent':True}
    (ROOT/'results.json').write_text(json.dumps(results,indent=2))
    race_lines=[]
    for name in ['A1','A2','B1u','B1']:
        vals=focused_races[name]
        race_lines.append(f"- {name}: {', '.join(v['validationCode'] for v in vals)}; block(s) {', '.join(sorted({v['block'] for v in vals}))}.")
    (ROOT/'REPORT.md').write_text(f'''# Live Fabric E2E verification

DONE. All six selected concurrency scenarios passed, followed by all {count} existing E2E scenarios. Both Maven commands exited 0. The focused run excluded the other {count-6} scenarios by tag; the full run had zero failures, errors or skips. Production and test source stayed unchanged.

This is an independent reproduction on a new isolated network, separate from the repository's Fablo harness. It supplies live runtime evidence, not whole-service deduction or Java/Fabric-to-Lean refinement.

## Exact build and runtime

All {len(manifest['source_files'])} current src/pom files matched the successful build snapshot before deployment. The deployed jar was copied from that snapshot. Its SHA-256, also checked inside the running chaincode container, is `{jar}`. All {correspondence['production_class_count']} freshly compiled application class files match the jar entries byte for byte, with no extra or missing application classes. See [class correspondence](class-correspondence.json), [source hashes](source-hashes.json) and [deployment manifest](deployment-manifest.json).

Both peers and the orderer report Fabric 2.5.15. Their exact version/commit strings are in [results.json](results.json); image IDs/digests, downloaded tool hashes, package ID, deployment hashes and container IDs are in the deployment manifest. Chaincode Java version is recorded in logs/cc-java-version.log. Maven compiles the unchanged project with release 17; these commands execute test-compile and Failsafe directly, avoiding another package build or unit-test run. Command lines, PIDs, timestamps and exit codes are in logs/.

## Topology and oracle

One Org1MSP with two peers and one Raft orderer, channel my-channel1, chaincode vdrchaincode, contract VdrContract. One Org1 peer endorsement suffices. Gateway/Fabric traffic uses generated TLS identities and expected DNS aliases; CCaaS is plaintext inside the private task network. Peer0 binds only 127.0.0.1:7041 and orderer administration binds 127.0.0.1:17053 after conflict checks. Container names and ownership labels use a distinctive tdkproof project. The crypto path is explicitly supplied to the tests.

A0 sequential same-key updates reached version 2. B0 sequential cross-check allowed only the first update, leaving versions 1/0. The four races matched the frozen oracle:

{chr(10).join(race_lines)}

No endorsement/network failure substituted for a conflict. All race submissions used one attempt. The full suite reproduced those exact validation-code expectations again. [Focused race results](focused-race-results.json) and [full race results](full-race-results.json) retain transaction IDs and blocks. [Ledger evidence](ledger-race-evidence.json) checks all eight focused race transactions against peer validation bytes. Both peers returned identical copies of each exported block. [Read/write sets](rwset-evidence.json) show disjoint cross read/write sets for B1u and reciprocal overlap for B1. A single finite run does not establish arbitrary-schedule coverage or an implementation refinement theorem.

## Cleanup and reproduction

All {survival['before_count']} pre-existing container IDs and states, including emap-mockserver, my-daylio-cloud-1, my-daylio-postgres-1 and once-proxy, were preserved. [Survival evidence](unrelated-survival.json) records unrelated additions separately. Only recorded task-owned resources were removed by ID after ownership checks. No global stop/down, prune, unowned deletion, install/configuration change, commit, push or PR occurred. Generated crypto and live ledger stores are removed; exported public evidence remains. Repository source hashes and Git status are unchanged.

[README.md](README.md) describes source-only setup and all overrides. `reproduce.py` requires successful tests, class/block/manifest evidence, cleanup, sanitization and final aggregation before returning 0. A fresh run emits validated results.json and REPORT.md. The original run's phase scripts executed against the real network. Wrapper failure propagation was checked synthetically; no second full network run was done solely to test packaging. The source package excludes generated crypto and is scanned for private-key PEM markers. Original provisioning attempts, if present, are recorded in ATTEMPTS.md and numbered logs.
''')
    print(json.dumps(results,indent=2))
if __name__=='__main__': aggregate()
