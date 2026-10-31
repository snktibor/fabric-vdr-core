#!/usr/bin/env python3
"""Capture exact clause IDs and available external proof reports; never run or certify Java proofs."""
from pathlib import Path
import hashlib,json,re,shutil
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT.parent/'java-service'
PROD=ROOT.parents[2]/'sinka-tibor-code-fabric-vdr-core/src/main/java/com/example/vdr'

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,x):p.write_text(json.dumps(x,indent=2)+'\n')

def main():
    data=json.loads((ROOT/'inputs/contracts-v4.json').read_text());catalog={};cases=[]
    reports=[]
    for p in JAVA.glob('**/result*.json'):
        try:
            x=json.loads(p.read_text()); xs=x if isinstance(x,list) else [x]
            for item in xs:
                if isinstance(item,dict) and 'name' in item:
                    reports.append((p,item))
        except (ValueError,OSError):pass
    for cls,methods in data.items():
        for method,items in methods.items():
            for case in items:
                counter={};clauses=[]
                for line in case['jml'].splitlines():
                    m=re.match(r'\s*@\s*(requires|ensures|assignable|signals_only|signals)\s+(.*)',line)
                    if m:
                        kind,body=m.groups(); counter[kind]=counter.get(kind,0)+1
                        tag={'requires':'R','ensures':'E','assignable':'A','signals':'S','signals_only':'O'}[kind]
                        name=case['name']+'.'+tag+str(counter[kind]).zfill(2)
                        catalog[name]={'kind':kind,'text':body,'class':cls,'method':method};clauses.append(name)
                digest=hashlib.sha256(case['jml'].encode()).hexdigest()
                matching=[{'path':str(p.relative_to(ROOT.parent)), 'report_sha256':sha(p), 'status':r.get('status'),
                           'proof_sha256':r.get('proof_sha256')} for p,r in reports if r.get('name')==case['name'] and r.get('contract_sha256')==digest]
                ok=any(r['status']=='PROVED_AND_FRESHLY_REPLAYED' for r in matching)
                cases.append({'name':case['name'],'method':method,'jml_sha256':digest,'clauses':clauses,
                    'evidence_status':'matching_replayed_result_found' if ok else 'not_discharged_by_inspected_evidence',
                    'matching_reports':matching,
                    'qualification':'Report inspection only. Shared adapter environment, full dependency closure, transfer and KeY replay are not independently certified here.'})
    save(ROOT/'inputs/jml-clause-catalog.json',catalog)
    save(ROOT/'inputs/jml-case-coverage.json',{'captured_utc':datetime.now(timezone.utc).isoformat(),
      'frozen_contract_sha256':sha(ROOT/'inputs/contracts-v4.json'),'live_contract_sha256':sha(JAVA/'contracts-v4.json'),
      'case_count':len(cases),'cases':cases})
    files=['logic/RevRegEntryService.java','logic/ValidationService.java','logic/AuthorizationService.java',
      'logic/JwtValidator.java','logic/JwtPrincipal.java','logic/RevRegEntryVersion.java','ledger/BaseRegistry.java',
      'ledger/JwtNonceRegistry.java','util/JsonUtil.java','contract/VdrContract.java','model/RevRegEntryRecord.java']
    hashes={}
    dest=ROOT/'inputs/source';dest.mkdir(exist_ok=True)
    for name in files:
        src=PROD/name;dst=dest/Path(name).name;shutil.copyfile(src,dst)
        hashes[str(src.relative_to(ROOT.parents[2]))]={'sha256':sha(src),'snapshot':'inputs/source/'+dst.name}
    save(ROOT/'inputs/source-evidence.json',hashes)
    adapter=JAVA/'prepared/source/com/example/vdr'
    for sub in ['ledger/RevRegEntryRegistry.java','ledger/RevRegDefRegistry.java','logic/ValidationService.java']:
        src=adapter/sub
        if src.exists():
            dst=ROOT/'inputs'/('adapter-'+Path(sub).name);shutil.copyfile(src,dst)
    print(json.dumps({'cases':len(cases),'matching_replayed_report':sum(c['evidence_status']=='matching_replayed_result_found' for c in cases),
                      'not_discharged':sum(c['evidence_status']!='matching_replayed_result_found' for c in cases)}))

if __name__=='__main__':main()
