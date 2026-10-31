#!/usr/bin/env python3
from control import *
import yaml,copy,tarfile,io
PROJECT=os.environ.get('TDK_PROOF_PROJECT','tdkproof-tibor-20261026')
LABEL='tdkproof.owner'
ORG=ROOT/'organizations/peerOrganizations/org1.example.com'
ORDER=ROOT/'organizations/ordererOrganizations/example.com/orderers/orderer.example.com'
DC=['docker','compose','-p',PROJECT,'-f',str(ROOT/'compose.yaml')]
def save_owned():
    ids=subprocess.check_output(['docker','ps','-aq','--no-trunc','--filter',f'label={LABEL}={PROJECT}'],text=True).split()
    nets=subprocess.check_output(['docker','network','ls','-q','--no-trunc','--filter',f'label={LABEL}={PROJECT}'],text=True).split()
    old=json.loads((ROOT/'owned.json').read_text()) if (ROOT/'owned.json').exists() else {}
    old['retired_containers']=sorted(set(x for x in old.get('retired_containers',[])+old.get('containers',[]) if not any(y.startswith(x) for y in ids)))
    old.update(containers=ids,networks=nets)
    (ROOT/'owned.json').write_text(json.dumps(old,indent=2))
def config():
    for port in [7041,17053]:
        with socket.socket() as s: s.bind(('127.0.0.1',port))
    (ROOT/'port-check.json').write_text(json.dumps({'ports':[7041,17053],'bind':'127.0.0.1','available':True,'time':time.time()}))
    crypt={'OrdererOrgs':[{'Name':'Orderer','Domain':'example.com','EnableNodeOUs':True,'Specs':[{'Hostname':'orderer','SANS':['localhost','127.0.0.1']}]}], 'PeerOrgs':[{'Name':'Org1','Domain':'org1.example.com','EnableNodeOUs':True,'Template':{'Count':2,'SANS':['localhost','127.0.0.1']},'Users':{'Count':1}}]}
    (ROOT/'crypto-config.yaml').write_text(yaml.safe_dump(crypt))
    run('cryptogen',['bin/cryptogen','generate','--config=crypto-config.yaml','--output=organizations'])
    cfg=yaml.safe_load((ROOT/'downloads/samples-configtx.yaml').read_text())
    cfg['Organizations']=cfg['Organizations'][:2]
    cfg['Profiles']['ChannelUsingRaft']['Application']['Organizations']=cfg['Organizations'][1:]
    cfg['Organizations'][1]['AnchorPeers']=[{'Host':'peer0.org1.example.com','Port':7051}]
    def paths(o):
        if isinstance(o,dict):
            for k,v in o.items(): o[k]=str(ROOT/v.removeprefix('../')) if isinstance(v,str) and v.startswith('../organizations/') else paths(v)
        elif isinstance(o,list): return [paths(x) for x in o]
        return o
    paths(cfg);(ROOT/'configtx.yaml').write_text(yaml.safe_dump(cfg))
    run('configtxgen',['bin/configtxgen','-configPath',str(ROOT),'-profile','ChannelUsingRaft','-outputBlock','my-channel1.block','-channelID','my-channel1'])
    samples=yaml.safe_load((ROOT/'downloads/samples-compose.yaml').read_text())
    services={}
    for name,orig in [('orderer',samples['services']['orderer.example.com']),('peer0',samples['services']['peer0.org1.example.com']),('peer1',samples['services']['peer0.org1.example.com'])]:
        svc=copy.deepcopy(orig)
        svc['container_name']=PROJECT+'-'+name
        svc['labels']={LABEL:PROJECT}
        svc['image']=PINNED_IMAGES['orderer'] if name=='orderer' else PINNED_IMAGES['peer']
        env=dict(x.split('=',1) for x in svc['environment'])
        env={k:v.replace('peer0.org1.example.com','peer1.org1.example.com') if name=='peer1' else v for k,v in env.items()}
        if name!='orderer':
            env['FABRIC_CFG_PATH']='/etc/hyperledger/peercfg'
            env['CORE_PEER_GOSSIP_BOOTSTRAP']=('peer1' if name=='peer0' else 'peer0')+'.org1.example.com:7051'
            env['CORE_VM_ENDPOINT']=''
        svc['environment']=env
        svc['ports']=['127.0.0.1:17053:7053'] if name=='orderer' else (['127.0.0.1:7041:7051'] if name=='peer0' else [])
        alias='orderer.example.com' if name=='orderer' else name+'.org1.example.com'
        svc['networks']={'proof':{'aliases':[alias]}}
        data=ROOT/'data'/name;data.mkdir(parents=True,exist_ok=True)
        if name=='orderer': svc['volumes']=[f'{ORDER}/msp:/var/hyperledger/orderer/msp:ro',f'{ORDER}/tls:/var/hyperledger/orderer/tls:ro',f'{data}:/var/hyperledger/production/orderer',f'{ROOT}:/proof:ro']
        else: svc['volumes']=[f'{ORG}/peers/{name}.org1.example.com:/etc/hyperledger/fabric:ro',f'{ROOT}/config/core.yaml:/etc/hyperledger/peercfg/core.yaml:ro',f'{data}:/var/hyperledger/production',f'{ROOT}:/proof:ro']
        services[name]=svc
    compose={'services':services,'networks':{'proof':{'name':PROJECT,'labels':{LABEL:PROJECT}}}}
    (ROOT/'compose.yaml').write_text(yaml.safe_dump(compose))
    run('compose-config',DC+['config'])
def up():
    try: run('compose-create',DC+['create'])
    finally: save_owned()
    run('compose-start',DC+['start'])
    time.sleep(4)
    run('orderer-join',['bin/osnadmin','channel','join','--channelID','my-channel1','--config-block','my-channel1.block','-o','localhost:17053','--ca-file',str(ORDER/'tls/ca.crt'),'--client-cert',str(ORDER/'tls/server.crt'),'--client-key',str(ORDER/'tls/server.key')])
    for p in ['peer0','peer1']: peer(p,'join-'+p,['channel','join','-b','/proof/my-channel1.block'])
def peer(p,name,args):
    return run(name,['docker','exec','-e','CORE_PEER_MSPCONFIGPATH=/proof/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp',PROJECT+'-'+p,'peer',*args])
def deploy():
    pkg=ROOT/'package';pkg.mkdir(exist_ok=True)
    (pkg/'connection.json').write_text(json.dumps({'address':'cc:9999','dial_timeout':'10s','tls_required':False}))
    (pkg/'metadata.json').write_text(json.dumps({'type':'ccaas','label':'vdrproof_1'}))
    with tarfile.open(pkg/'code.tar.gz','w:gz') as t: t.add(pkg/'connection.json',arcname='connection.json')
    with tarfile.open(ROOT/'vdrproof.tgz','w:gz') as t:
        for x in ['metadata.json','code.tar.gz']: t.add(pkg/x,arcname=x)
    packageid=peer('peer0','package-id',['lifecycle','chaincode','calculatepackageid','/proof/vdrproof.tgz']).strip()
    (ROOT/'package-id.txt').write_text(packageid+'\n')
    for p in ['peer0','peer1']: peer(p,'install-'+p,['lifecycle','chaincode','install','/proof/vdrproof.tgz'])
    # Preserve repo Dockerfile except pinning its existing base to the downloaded digest.
    base=json.loads((ROOT/'logs/image-inspect.log').read_text())[2]['RepoDigests'][0]
    df=(ROOT/'artifact/Dockerfile').read_text().replace('eclipse-temurin:17-jre',base)
    (ROOT/'artifact/Dockerfile').write_text(df)
    run('build-chaincode-image',['docker','build','--label',f'{LABEL}={PROJECT}','-t',PROJECT+'-chaincode:1',str(ROOT/'artifact')])
    iid=subprocess.check_output(['docker','image','inspect','--format','{{.Id}}',PROJECT+'-chaincode:1'],text=True).strip()
    owned=json.loads((ROOT/'owned.json').read_text());owned['images']=[iid];(ROOT/'owned.json').write_text(json.dumps(owned,indent=2))
    compose=yaml.safe_load((ROOT/'compose.yaml').read_text())
    compose['services']['cc']={'image':iid,'container_name':PROJECT+'-cc','labels':{LABEL:PROJECT},'environment':{'CHAINCODE_SERVER_ADDRESS':'0.0.0.0:9999','CORE_CHAINCODE_ID_NAME':packageid,'CORE_PEER_TLS_ENABLED':'false'},'networks':{'proof':{'aliases':['cc']}}}
    (ROOT/'compose.yaml').write_text(yaml.safe_dump(compose))
    try: run('cc-create',DC+['create','cc'])
    finally: save_owned()
    run('cc-start',DC+['start','cc'])
    order=['-o','orderer.example.com:7050','--ordererTLSHostnameOverride','orderer.example.com','--tls','--cafile','/proof/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/tls/ca.crt']
    common=['--channelID','my-channel1','--name','vdrchaincode','--version','1','--sequence','1','--signature-policy',"OR('Org1MSP.peer')"]
    peer('peer0','approve',['lifecycle','chaincode','approveformyorg',*order,*common,'--package-id',packageid])
    peer('peer0','commit',['lifecycle','chaincode','commit',*order,*common])
    for p in ['peer0','peer1']: peer(p,'querycommitted-'+p,['lifecycle','chaincode','querycommitted','--channelID','my-channel1','--name','vdrchaincode','--output','json'])
    run('cc-java-version',['docker','exec',PROJECT+'-cc','java','-version'])
    run('cc-jar-sha',['docker','exec',PROJECT+'-cc','sha256sum','/root/chaincode-java/chaincode/chaincode.jar'])
    run('deployed-inspect',['docker','inspect',*json.loads((ROOT/'owned.json').read_text())['containers']])
    for name,exe in [('peer0','peer'),('peer1','peer'),('orderer','orderer')]:
        run('runtime-version-'+name,['docker','exec',PROJECT+'-'+name,exe,'version'])
    run('source-git-commit',['git','rev-parse','HEAD'],cwd=REPO)
    run('source-git-branch',['git','branch','--show-current'],cwd=REPO)
def maven_executable():
    return pathlib.Path(os.environ.get('MAVEN_EXECUTABLE',str(BUILD/'apache-maven-3.9.9/bin/mvn'))).resolve()
def tests():
    maven=maven_executable()
    if not (ROOT/'m2').exists():
        seed=pathlib.Path(os.environ.get('TDK_PROOF_M2_SEED',str(BUILD/'m2')))
        if seed.exists(): shutil.copytree(seed,ROOT/'m2')
        else: (ROOT/'m2').mkdir()
    args=[str(maven),'-B','-Pe2e','-Dmaven.repo.local='+str(ROOT/'m2'),'-Dvdr.e2e.cryptoPath='+str(ORG),'-Dit.test=RunCucumberIT','-Dcucumber.plugin=pretty,json:target/cucumber.json','test-compile','failsafe:integration-test','failsafe:verify']
    run('focused-e2e',args+['-Dcucumber.filter.tags=@concurrency'],cwd=ROOT/'project',timeout=1200)
    for d in ['failsafe-reports','cucumber.json']:
        src=ROOT/'project/target'/d
        if src.is_dir(): shutil.copytree(src,ROOT/'focused-failsafe-reports',dirs_exist_ok=True)
        else: shutil.copy2(src,ROOT/'focused-cucumber.json')
    from evidence import check
    check(ROOT/'logs/focused-e2e.log','focused')
    run('full-e2e',args,cwd=ROOT/'project',timeout=1800)
    shutil.copytree(ROOT/'project/target/failsafe-reports',ROOT/'full-failsafe-reports',dirs_exist_ok=True)
    shutil.copy2(ROOT/'project/target/cucumber.json',ROOT/'full-cucumber.json')
def capture():
    owned=json.loads((ROOT/'owned.json').read_text())
    for id in owned['containers']:
        name=subprocess.check_output(['docker','inspect','--format','{{.Name}}',id],text=True).strip().lstrip('/')
        run('container-log-'+name,['docker','logs',id])
    run('containers-during',['docker','ps','-a','--no-trunc','--format','{{json .}}'])
def cleanup():
    owned=json.loads((ROOT/'owned.json').read_text())
    for id in owned['containers']:
        result=subprocess.run(['docker','inspect',id],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        if result.returncode:
            assert 'no such object' in result.stderr.lower();continue
        obj=json.loads(result.stdout)[0]
        assert obj['Config']['Labels'].get(LABEL)==PROJECT
        run('remove-container-'+id[:12],['docker','rm','-fv',id])
    for id in owned['networks']:
        result=subprocess.run(['docker','network','inspect',id],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        if result.returncode:
            assert 'not found' in result.stderr or 'No such network' in result.stderr;continue
        obj=json.loads(result.stdout)[0]
        assert obj['Labels'].get(LABEL)==PROJECT
        run('remove-network-'+id[:12],['docker','network','rm',id])
    for id in owned.get('images',[]):
        result=subprocess.run(['docker','image','inspect',id],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        if result.returncode:
            assert 'No such image' in result.stderr;continue
        obj=json.loads(result.stdout)[0]
        assert obj['Config']['Labels'].get(LABEL)==PROJECT
        run('remove-image',['docker','image','rm',id])
    after=run('containers-after',['docker','ps','-a','--no-trunc','--format','{{json .}}'])
    before=json.loads((ROOT/'containers-before.json').read_text());after=[json.loads(x) for x in after.splitlines()]
    compare={'before_count':len(before),'after_count':len(after),'same_ids':set(x['ID'] for x in before)==set(x['ID'] for x in after),'same_states':{x['ID']:x['State'] for x in before}=={x['ID']:x['State'] for x in after},'running_before':[{'ID':x['ID'],'name':x['Names']} for x in before if x['State']=='running'],'running_after':[{'ID':x['ID'],'name':x['Names']} for x in after if x['State']=='running']}
    before_ids={x['ID']:x for x in before};after_ids={x['ID']:x for x in after}
    compare['initial_ids_preserved']=set(before_ids)<=set(after_ids)
    compare['initial_states_unchanged']=all(id in after_ids and x['State']==after_ids[id]['State'] for id,x in before_ids.items())
    compare['unrelated_additions']=[{'ID':id,'name':after_ids[id]['Names'],'image':after_ids[id]['Image']} for id in after_ids.keys()-before_ids.keys()]
    (ROOT/'unrelated-survival.json').write_text(json.dumps(compare,indent=2));assert compare['initial_ids_preserved'] and compare['initial_states_unchanged']
    assert hashes(REPO)==json.loads((ROOT/'source-hashes.json').read_text())
    run('git-status-after',['git','status','--porcelain=v1'],cwd=REPO)
def sanitize():
    owned=json.loads((ROOT/'owned.json').read_text()) if (ROOT/'owned.json').exists() else {'containers':[]}
    for id in owned['containers']:
        assert subprocess.run(['docker','inspect',id],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0, 'cleanup must finish before deleting crypto'
    removed=[]
    for name in ['organizations','data']:
        path=ROOT/name
        if path.exists():
            try: shutil.rmtree(path)
            except PermissionError:
                assert name=='data' and path.resolve()==ROOT/'data' and not path.is_symlink()
                # Only this task's root-owned ledger bind directory is exposed to the helper.
                helper=run('sanitizer-create',['docker','create','--name',PROJECT+'-sanitizer','--network','none','--label',f'{LABEL}={PROJECT}','--mount',f'type=bind,src={path},dst=/owned-data','--entrypoint','/bin/sh',PINNED_IMAGES['peer'],'-c','find /owned-data -mindepth 1 -delete']).strip()
                owned['containers'].append(helper);(ROOT/'owned.json').write_text(json.dumps(owned,indent=2))
                try:
                    run('sanitizer-start',['docker','start','-a',helper])
                    detail=json.loads(run('sanitizer-inspect',['docker','inspect',helper]))[0]
                    assert detail['State']['ExitCode']==0
                finally: run('sanitizer-remove',['docker','rm','-fv',helper])
                shutil.rmtree(path)
            removed.append(name)
    (ROOT/'sanitization.json').write_text(json.dumps({'removed_generated_directories':removed,'private_keys_retained':False},indent=2))
    print('Removed generated crypto and live ledger stores; exported public block evidence retained.')
if __name__=='__main__':
    (ROOT/'logs'/('driver-'+sys.argv[1]+'.pid')).write_text(str(os.getpid()))
    globals()[sys.argv[1]]()
