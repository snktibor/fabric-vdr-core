#!/usr/bin/env python3
"""Compile the sealed bridge and unchanged dependencies with a fresh Lean search path."""
from __future__ import annotations
import hashlib, json, os, re, subprocess, tempfile, traceback
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
DEPS = ['Model','Proofs','SkipModel','SkipProofs','LedgerModel','LedgerSpec',
        'LedgerSpecCorrection','LedgerProofs','AtomicModel','AtomicSpec','AtomicProofs']
MODULES = ['BridgeModel','BridgeSpec','BridgeProofs','BridgeWitnesses','BridgeAudit']
TARGETS = dict(zip(
    ['eligibility','write_image','read_coverage','checked_provenance','validation',
     'apply_refinement','step_refinement','run_refinement','commit_refinement','no_reset','safety','end_to_end'],
    ['EligibilitySpec','WriteImageSpec','ReadCoverageSpec','CheckedProvenanceSpec','ValidationSpec',
     'ApplySpec','StepSpec','RunSpec','CommitSpec','NoResetSpec','SafetySpec','EndToEndSpec']))

def digest(p):
    with p.open('rb') as f:
        return hashlib.file_digest(f,'sha256').hexdigest()

def require(p, msg):
    if not p: raise RuntimeError(msg)

def write_json(p, value):
    tmp=p.with_suffix(p.suffix+'.tmp')
    tmp.write_text(json.dumps(value,indent=2)+'\n')
    tmp.replace(p)

def seals():
    hashes={}
    for line in (ROOT/'frozen/semantic.sha256').read_text().splitlines():
        expected,name=line.split(maxsplit=1)
        path=Path(name)
        require(not path.is_absolute() and '..' not in path.parts, 'Nonrelative seal path')
        require(digest(ROOT/path)==expected, 'Frozen input changed: '+name)
        hashes[name]=expected
    require(set(['CONTRACT.md','BridgeModel.lean','BridgeSpec.lean','toolchain-lock.json'])<=hashes.keys(),
            'Missing semantic seals')
    return hashes

def source_hashes():
    files=[*ROOT.glob('*.lean'),*ROOT.glob('*.md'),*ROOT.glob('scripts/*'),*ROOT.glob('frozen/*'),
           *(ROOT/'inputs').rglob('*'),ROOT/'lean-toolchain',ROOT/'toolchain-lock.json']
    return {str(p.relative_to(ROOT)):digest(p) for p in sorted(files) if p.is_file()}

def main():
    (ROOT/'logs').mkdir(exist_ok=True);(ROOT/'build').mkdir(exist_ok=True)
    write_json(ROOT/'result.json',{'success':False,'status':'running'})
    for name in ['theorem-inventory.json']:
        (ROOT/name).unlink(missing_ok=True)
    log_path=ROOT/'logs/replay.log'
    try:
        frozen=seals(); before=source_hashes()
        lock=json.loads((ROOT/'toolchain-lock.json').read_text())
        install=Path(os.environ.get('LEAN_TOOLCHAIN_DIR',ROOT.parent/'lean-reconstruction'/lock['installation_path'])).resolve()
        for name, expected in {**lock['executable_sha256'],**lock['runtime_sha256']}.items():
            require(digest(install/name)==expected,'Pinned toolchain mismatch: '+name)
        lean=install/'bin/lean'
        version=subprocess.check_output([str(lean),'--version'],text=True).strip()
        require(version==lock['lean_version_output'],'Lean version mismatch')
        original_status={}
        for name,expected in json.loads((ROOT/'frozen/dependency-originals.json').read_text()).items():
            p=ROOT.parent/name
            if p.exists():
                require(digest(p)==expected,'Original dependency changed: '+name)
                original_status[name]='identical'
            else: original_status[name]='absent in portable layout; sealed copy verified'
        sources=[(ROOT/'deps',x) for x in DEPS]+[(ROOT,x) for x in MODULES]
        for folder,module in sources:
            code=(folder/(module+'.lean')).read_text()
            require(not re.search(r'\b(sorry|admit|native_decide|axiom|unsafe|implemented_by|extern)\b|debug\.skipKernelTC',code),
                    'Forbidden proof mechanism in '+module)
            for imported in re.findall(r'^import\s+(\S+)',code,re.M):
                require(imported in set(DEPS+MODULES+['Std','Lean']),'Unexpected import '+imported)
        build=Path(tempfile.mkdtemp(prefix='fresh-',dir=ROOT/'build'))
        env=dict(os.environ,LEAN_PATH=str(build),LEAN_SYSROOT=str(install))
        commands=[]; audit=''
        with log_path.open('w') as log:
            log.write(version+'\nPinned executable and runtime hashes verified.\n')
            for folder,module in sources:
                path=folder/(module+'.lean'); rel=str(path.relative_to(ROOT))
                cmd=[str(lean),'--trust=0','-o',str(build/(module+'.olean')),rel]
                log.write('MODULE '+rel+'\n');log.flush()
                r=subprocess.run(cmd,cwd=ROOT,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
                log.write(r.stdout+'EXIT '+str(r.returncode)+'\n');log.flush()
                commands.append({'source':rel,'output':str((build/(module+'.olean')).relative_to(ROOT)),'exit_code':r.returncode})
                require(r.returncode==0,'Kernel check failed: '+module)
                require('declaration uses' not in r.stdout,'Placeholder warning: '+module)
                if module=='BridgeAudit': audit=r.stdout
        (ROOT/'logs/axioms.log').write_text(audit)
        all_axioms={}
        for name in re.findall(r'^AUDIT_THEOREM (.+)$',audit,re.M):
            m=re.search(re.escape("'"+name+"'")+r' (?:does not depend on any axioms|depends on axioms:\s*\[([^\]]*)\])',audit)
            require(m is not None,'Missing axiom audit: '+name)
            axioms=sorted(x.strip() for x in (m.group(1) or '').split(',') if x.strip())
            allowed={'propext','Quot.sound'}
            if name=='Reconstruction.rejected_has_prior_committed_writer': allowed.add('Classical.choice')
            require(set(axioms)<=allowed,'Unexpected dependencies: '+name+': '+str(axioms))
            all_axioms[name]=axioms
        require(all_axioms,'Empty axiom audit')
        inventory=[]
        for module in ['BridgeProofs','BridgeWitnesses']:
            ns=''
            for number,line in enumerate((ROOT/(module+'.lean')).read_text().splitlines(),1):
                n=re.match(r'^namespace\s+(\S+)',line)
                if n: ns=n.group(1)
                n=re.match(r'^theorem\s+(\w+)',line)
                if not n: continue
                short=n.group(1); full=ns+'.'+short
                require(full in all_axioms,'Theorem absent from audit: '+full)
                spec=TARGETS.get(short) if ns=='ServiceTraceBridge' else None
                if spec:
                    require(f'#check ({full} : ServiceTraceBridge.{spec})' in (ROOT/'BridgeAudit.lean').read_text(),
                            'Missing exact target check: '+full)
                inventory.append({'name':full,'source':module+'.lean','line':number,
                                  'role':'target' if spec else ('witness' if module=='BridgeWitnesses' else 'auxiliary'),
                                  'spec':spec,'axioms':all_axioms[full]})
        require(sum(x['role']=='target' for x in inventory)==12,'Incomplete target inventory')
        require(seals()==frozen and source_hashes()==before,'Source changed during replay')
        write_json(ROOT/'theorem-inventory.json',{'theorems':inventory,'all_environment_theorem_axioms':all_axioms})
        write_json(ROOT/'result.json',{'success':True,'status':'kernel_checked','finished_utc':datetime.now(timezone.utc).isoformat(),
            'lean_version':version,'frozen_sha256':frozen,'source_sha256':before,'original_dependencies':original_status,
            'commands':commands,'counts':{'targets':12,'explicit_bridge_theorems':len(inventory),
            'witnesses':sum(x['role']=='witness' for x in inventory),'audited_environment_theorems':len(all_axioms),
            'audited_bridge_environment_theorems':sum(x.startswith('ServiceTraceBridge.') for x in all_axioms)},
            'toolchain_lock_sha256':digest(ROOT/'toolchain-lock.json'),'build':str(build.relative_to(ROOT)),
            'scope':'Conditional service-trace composition. Java Hoare, runtime/codec, platform, authentication and fidelity are named boundaries.'})
        print(json.dumps({'success':True,'targets':12,'explicit_theorems':len(inventory),'build':str(build.relative_to(ROOT))}))
    except Exception as error:
        write_json(ROOT/'result.json',{'success':False,'status':'failed','error':str(error)})
        print(traceback.format_exc())
        raise SystemExit(1)

if __name__=='__main__': main()
