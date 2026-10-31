#!/usr/bin/env python3
"""Replay an isolated relocated source copy; check stale-success invalidation on a seal failure."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]

def main():
    lock=json.loads((ROOT/'toolchain-lock.json').read_text())
    install=Path(os.environ.get('LEAN_TOOLCHAIN_DIR',ROOT.parent/'lean-reconstruction'/lock['installation_path'])).resolve()
    base=Path(tempfile.mkdtemp(prefix='relocated-',dir=ROOT/'build'))
    dest=base/'service-trace-bridge';dest.mkdir()
    for name in ['deps','inputs','frozen','scripts']:
        shutil.copytree(ROOT/name,dest/name)
    for p in [*ROOT.glob('*.lean'),*ROOT.glob('*.md'),ROOT/'lean-toolchain',ROOT/'toolchain-lock.json']:
        shutil.copyfile(p,dest/p.name)
    env=dict(os.environ,LEAN_TOOLCHAIN_DIR=str(install))
    command=['python3',str(dest/'scripts/replay.py')]
    result=subprocess.run(command,cwd='/',env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
    (ROOT/'logs/portability.log').write_text(result.stdout)
    if result.returncode: raise RuntimeError('Relocated fresh replay failed')
    good=json.loads((dest/'result.json').read_text())
    if not good['success']: raise RuntimeError('Relocated replay did not pass')
    (ROOT/'logs/portable-result.json').write_text(json.dumps(good,indent=2)+'\n')
    target=dest/'BridgeSpec.lean';target.write_bytes(target.read_bytes()+b'\n')
    negative=subprocess.run(command,cwd='/',env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
    (ROOT/'logs/failure-invalidation.log').write_text(negative.stdout)
    bad=json.loads((dest/'result.json').read_text())
    if negative.returncode==0 or bad.get('success') or (dest/'theorem-inventory.json').exists():
        raise RuntimeError('Seal failure retained stale success')
    if 'Frozen input changed: BridgeSpec.lean' not in bad.get('error',''):
        raise RuntimeError('Negative replay failed for an unexpected reason')
    (ROOT/'portability.json').write_text(json.dumps({'success':True,
      'relocated_directory':str(dest.relative_to(ROOT)),'working_directory':'/',
      'positive_counts':good['counts'],'positive_result_sha256':hashlib.sha256((ROOT/'logs/portable-result.json').read_bytes()).hexdigest(),
      'negative_control':'One newline appended only to relocated frozen spec; exact frozen-hash failure, result invalidated and inventory removed.',
      'negative_status':bad},indent=2)+'\n')
    print(json.dumps({'portable_fresh_replay':True,'seal_failure_invalidates_stale_result':True}))
if __name__=='__main__':main()
