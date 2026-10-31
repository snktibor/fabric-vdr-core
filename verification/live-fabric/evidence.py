#!/usr/bin/env python3
from harness import *
import re,base64,collections

def races(log):
    scenario=None;out={}
    for line in log.read_text().splitlines():
        match=re.match(r'Scenario: (A0|A1|A2|B0|B1u|B1)\.',line)
        if match: scenario=match[1];out.setdefault(scenario,[])
        if 'Concurrent submission result: ' in line:
            text=line.split('Concurrent submission result: ',1)[1]
            values=dict(re.findall(r'(\w+)=([^,\]]+)',text));out[scenario].append(values)
    return out

def check(log,name):
    found=races(log)
    expected={'A1':['VALID','MVCC_READ_CONFLICT'],'A2':['VALID','MVCC_READ_CONFLICT'],'B1u':['VALID','VALID'],'B1':['VALID','MVCC_READ_CONFLICT']}
    for key,codes in expected.items():
        assert sorted(x['validationCode'] for x in found[key])==sorted(codes),(key,found.get(key))
        assert all(x['attempts']=='1' and x['error']=='none' for x in found[key])
    (ROOT/(name+'-race-results.json')).write_text(json.dumps(found,indent=2))
    return found

def blocks():
    found=check(ROOT/'logs/focused-e2e.log','focused')
    folder=ROOT/'blocks';folder.mkdir(exist_ok=True)
    nums=sorted(set(int(x['block']) for values in found.values() for x in values))
    for num in nums:
        raw=peer('peer0','peer-block-'+str(num),['chaincode','query','-C','my-channel1','-n','qscc','-c',json.dumps({'Args':['GetBlockByNumber','my-channel1',str(num)]}),'--hex'])
        hexlines=[x.strip() for x in raw.splitlines() if re.fullmatch('[0-9a-fA-F]{100,}',x.strip())]
        assert len(hexlines)==1,raw[:1000]
        (folder/f'{num}.pb').write_bytes(bytes.fromhex(hexlines[0]))
        replica=peer('peer1','peer1-block-'+str(num),['chaincode','query','-C','my-channel1','-n','qscc','-c',json.dumps({'Args':['GetBlockByNumber','my-channel1',str(num)]}),'--hex'])
        replicahex=[x.strip() for x in replica.splitlines() if re.fullmatch('[0-9a-fA-F]{100,}',x.strip())]
        assert len(replicahex)==1 and bytes.fromhex(replicahex[0])==(folder/f'{num}.pb').read_bytes()
        run('decode-block-'+str(num),['bin/configtxlator','proto_decode','--input',str(folder/f'{num}.pb'),'--type','common.Block','--output',str(folder/f'{num}.json')])
    checked=[]
    codes={'VALID':0,'MVCC_READ_CONFLICT':11}
    for scenario,values in found.items():
        for v in values:
            obj=json.loads((folder/(v['block']+'.json')).read_text())
            flags=base64.b64decode(obj['metadata']['metadata'][2])
            txids=[x['payload']['header']['channel_header']['tx_id'] for x in obj['data']['data']]
            pos=txids.index(v['txId'])
            assert flags[pos]==codes[v['validationCode']]
            checked.append({'scenario':scenario,**v,'ledger_validation_byte':flags[pos],'block_sha256':hashlib.sha256((folder/(v['block']+'.pb')).read_bytes()).hexdigest()})
    relations={}
    for scenario in ['A1','A2','B1u','B1']:
        sets=[]
        for v in found[scenario]:
            obj=json.loads((folder/(v['block']+'.json')).read_text())
            tx=next(x for x in obj['data']['data'] if x['payload']['header']['channel_header']['tx_id']==v['txId'])
            ns=tx['payload']['data']['actions'][0]['payload']['action']['proposal_response_payload']['extension']['results']['ns_rwset']
            rw=next(x['rwset'] for x in ns if x['namespace']=='vdrchaincode')
            sets.append({k:[x['key'] for x in rw[k]] for k in ['reads','writes']})
        cross=[sorted(set(sets[0]['reads'])&set(sets[1]['writes'])),sorted(set(sets[1]['reads'])&set(sets[0]['writes']))]
        assert all(bool(x)==(scenario!='B1u') for x in cross)
        relations[scenario]={'transactions':sets,'read_against_other_write':cross}
    (ROOT/'rwset-evidence.json').write_text(json.dumps(relations,indent=2))
    (ROOT/'ledger-race-evidence.json').write_text(json.dumps(checked,indent=2))

def classes():
    import zipfile
    root=ROOT/'project/target/classes'
    with zipfile.ZipFile(ROOT/'artifact/chaincode.jar') as z:
        classes={str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in (root/'io/github/snktibor/vdr').rglob('*.class')}
        assert classes
        for name,sha in classes.items(): assert hashlib.sha256(z.read(name)).hexdigest()==sha,name
        jars={n for n in z.namelist() if n.startswith('io/github/snktibor/vdr/') and n.endswith('.class')}
        assert jars==set(classes)
    (ROOT/'class-correspondence.json').write_text(json.dumps({'all_identical':True,'production_class_count':len(classes),'classes':classes},indent=2))
    print('Fresh production classes exactly match deployed jar:',len(classes))

def manifest():
    images=json.loads((ROOT/'logs/image-inspect.log').read_text())
    records=[p for p in (ROOT/'logs').glob('deployed-inspect*.json') if json.loads(p.read_text()).get('exitcode')==0]
    latest=max(records,key=lambda p:json.loads(p.read_text())['end'])
    containers=json.loads(latest.with_suffix('.log').read_text())
    data={'source_files':json.loads((ROOT/'source-hashes.json').read_text()),'source_tree_sha256':hashlib.sha256((ROOT/'source-hashes.json').read_bytes()).hexdigest(),'jar_sha256':(ROOT/'jar.sha256').read_text().split()[0],'samples_commit':(ROOT/'samples-commit.txt').read_text().strip(),'fabric_images':[{'id':x['Id'],'digests':x['RepoDigests']} for x in images], 'deployed_containers':[{'id':x['Id'],'name':x['Name'],'image':x['Image']} for x in containers],'ccaas_package_id':(ROOT/'package-id.txt').read_text().strip()}
    files=['downloads/fabric.tar.gz','bin/peer','bin/orderer','bin/cryptogen','bin/configtxgen','bin/osnadmin','configtx.yaml','crypto-config.yaml','compose.yaml','my-channel1.block','vdrproof.tgz','artifact/Dockerfile','artifact/entrypoint.sh']
    data['deployment_files']={name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in files}
    (ROOT/'deployment-manifest.json').write_text(json.dumps(data,indent=2))
if __name__=='__main__': globals()[sys.argv[1]]()
