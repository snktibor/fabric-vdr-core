#!/usr/bin/env python3
import os,sys,json,hashlib,pathlib,subprocess,time,shutil,socket,signal
ROOT=pathlib.Path(__file__).resolve().parent
REPO=pathlib.Path(os.environ.get('TDK_PROOF_REPO',str(ROOT.parents[2]/'sinka-tibor-code-fabric-vdr-core')))
BUILD=pathlib.Path(os.environ.get('TDK_PROOF_BUILD',str(REPO.parent/'work/tibor-lean-grind/java-production/build')))
PINNED_IMAGES={
 'peer':'hyperledger/fabric-peer@sha256:c48323777139fcabe77a98cf4eeaf4466827dec225f754064f52f22395a8bd89',
 'orderer':'hyperledger/fabric-orderer@sha256:72697d099f45b8e57f86d9a69fbedb683cd5888bc7b28ec01695773d0433b791',
 'java':'eclipse-temurin@sha256:e04fa72be13b257a59fdec6dc14af78c8f80f035d3c1ed4e03e45552a231d4c8'
}
os.chdir(ROOT)
(ROOT/'logs').mkdir(exist_ok=True)
MANAGED_GROUP='TDK_PROOF_MANAGED_PROCESS_GROUP'
def kill_group(pid):
    try: os.killpg(pid,signal.SIGKILL)
    except ProcessLookupError: pass
def run(name,args,env=None,cwd=ROOT,timeout=600):
    original=name
    attempt=1
    while (ROOT/'logs'/f'{name}.log').exists():
        attempt+=1;name=f'{original}.attempt{attempt}'
    log=ROOT/'logs'/f'{name}.log'
    # The outer phase owns the group. Nested logged commands stay in that group
    # so its timeout also stops Maven and any Java processes Maven has spawned.
    owns_group=os.environ.get(MANAGED_GROUP)!='1'
    child_env={**os.environ,**(env or {}),MANAGED_GROUP:'1'}
    info={'command':args,'cwd':str(cwd),'start':time.time(),'timeout_seconds':timeout,'owns_process_group':owns_group}
    with log.open('w') as f:
        p=subprocess.Popen(args,cwd=cwd,env=child_env,stdout=f,stderr=subprocess.STDOUT,start_new_session=owns_group)
        info['pid']=p.pid
        info['process_group']=p.pid if owns_group else os.getpgrp()
        (ROOT/'logs'/f'{name}.pid').write_text(str(p.pid))
        (ROOT/'logs'/f'{name}.json').write_text(json.dumps(info,indent=2))
        try:
            try: code=p.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                if owns_group: kill_group(p.pid)
                else: p.kill()
                p.wait();code=124
        finally:
            # A nested timeout may leave descendants after the phase exits.
            # Reap those before reproduce.py begins capture or cleanup.
            if owns_group: kill_group(p.pid)
    info.update(exitcode=code,end=time.time());(ROOT/'logs'/f'{name}.json').write_text(json.dumps(info,indent=2))
    print(name,code,flush=True)
    if code: raise RuntimeError(f'{name}: exit {code}; {log}')
    return log.read_text()
def hashes(base):
    files=[base/'pom.xml',*sorted((base/'src').rglob('*'))]
    return {str(x.relative_to(base)):hashlib.sha256(x.read_bytes()).hexdigest() for x in files if x.is_file()}
def snapshot_project():
    return pathlib.Path(os.environ['TDK_PROOF_SNAPSHOT']).resolve() if os.environ.get('TDK_PROOF_SNAPSHOT') else pathlib.Path((BUILD/'latest-verification.txt').read_text().strip())/'project'
def baseline():
    before=run('containers-before',['docker','ps','-a','--no-trunc','--format','{{json .}}'])
    (ROOT/'containers-before.json').write_text(json.dumps([json.loads(x) for x in before.splitlines()],indent=2))
    run('docker-info',['docker','info']);run('images-before',['docker','image','ls','--digests','--no-trunc'])
    run('git-status-before',['git','status','--porcelain=v1'],cwd=REPO)
    snap=snapshot_project()
    a,b=hashes(REPO),hashes(snap)
    (ROOT/'source-hashes.json').write_text(json.dumps(a,indent=2))
    assert a==b, 'src/pom mismatch with production build snapshot'
    shutil.copytree(snap,ROOT/'project',ignore=shutil.ignore_patterns('target'),dirs_exist_ok=True)
    (ROOT/'artifact').mkdir(exist_ok=True)
    shutil.copy2(snap/'target/chaincode.jar',ROOT/'artifact/chaincode.jar')
    for x in ['Dockerfile','entrypoint.sh']: shutil.copy2(REPO/x,ROOT/'artifact'/x)
    (ROOT/'artifact/target').mkdir(exist_ok=True)
    shutil.copy2(snap/'target/chaincode.jar',ROOT/'artifact/target/chaincode.jar')
    sha=hashlib.sha256((ROOT/'artifact/chaincode.jar').read_bytes()).hexdigest()
    (ROOT/'jar.sha256').write_text(sha+'  chaincode.jar\n')
    scenarios=[]
    for f in sorted((REPO/'src/test/resources/features').glob('*.feature')):
        for line in f.read_text().splitlines():
            if line.strip().startswith(('Scenario:','Scenario Outline:')): scenarios.append({'file':f.name,'scenario':line.strip()})
    (ROOT/'scenario-inventory.json').write_text(json.dumps(scenarios,indent=2))
    print('source matched',len(a),'files; jar',sha,flush=True)
def fetch():
    run('samples-ref',['git','ls-remote','https://github.com/hyperledger/fabric-samples.git','refs/heads/main'])
    observed=(ROOT/'logs/samples-ref.log').read_text().split()[0]
    sha='5789681b4f4d24e58fa40f19a69f5496892374b6' # reviewed/pinned official samples commit
    (ROOT/'samples-observed-main.txt').write_text(observed+'\n')
    (ROOT/'samples-commit.txt').write_text(sha+'\n')
    (ROOT/'downloads').mkdir(exist_ok=True)
    urls={
      'fabric.tar.gz':'https://github.com/hyperledger/fabric/releases/download/v2.5.15/hyperledger-fabric-linux-amd64-2.5.15.tar.gz',
      'samples-configtx.yaml':f'https://raw.githubusercontent.com/hyperledger/fabric-samples/{sha}/test-network/configtx/configtx.yaml',
      'samples-compose.yaml':f'https://raw.githubusercontent.com/hyperledger/fabric-samples/{sha}/test-network/compose/compose-test-net.yaml'}
    for name,url in urls.items(): run('download-'+name,['curl','-fL','--retry','2','--max-time','300','-o',str(ROOT/'downloads'/name),url])
    assert hashlib.sha256((ROOT/'downloads/fabric.tar.gz').read_bytes()).hexdigest()=='c0f58626ff73b15bfc9ab0cf2b78a7e60dda3c53051a1032f32ffb3111df677f'
    run('archive-list',['tar','tzf','downloads/fabric.tar.gz'])
    run('extract-fabric',['tar','xzf','downloads/fabric.tar.gz'])
    for img in PINNED_IMAGES.values():
        run('pull-'+img.split('/')[-1].replace(':','-'),['docker','pull',img],timeout=900)
    run('peer-version',['bin/peer','version']);run('cryptogen-version',['bin/cryptogen','version'])
    run('image-inspect',['docker','image','inspect',*PINNED_IMAGES.values()])
if __name__=='__main__': globals()[sys.argv[1]]()
