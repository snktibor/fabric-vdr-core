#!/usr/bin/env python3
"""Create a fresh owned run; return 0 only after validated post-cleanup aggregation."""
import pathlib,os,sys,shutil,subprocess,time,json
WORK=[('control.py','baseline'),('control.py','fetch'),('harness.py','config'),('harness.py','up'),('harness.py','deploy'),('harness.py','tests'),('evidence.py','classes'),('evidence.py','blocks'),('evidence.py','manifest')]
PHASE_TIMEOUTS={'tests':1800}
def run_workflow(run,invoke):
    status=0
    try:
        for script,step in WORK: invoke(script,step)
    except Exception as e: print(str(e),file=sys.stderr);status=1
    finally:
        if (run/'owned.json').exists():
            for step in ['capture','cleanup']:
                try: invoke('harness.py',step)
                except Exception as e: print(step+': '+str(e),file=sys.stderr);status=1
        else:
            # No network was created, but a successful result requires ownership evidence.
            status=1
        try: invoke('harness.py','sanitize')
        except Exception as e: print('sanitize: '+str(e),file=sys.stderr);status=1
    if status==0:
        try:
            invoke('finalize.py',None)
            result=json.loads((run/'results.json').read_text())
            assert result.get('validated') is True and (run/'REPORT.md').is_file(), 'aggregation did not emit validated results and report'
        except Exception as e: print('aggregation: '+str(e),file=sys.stderr);status=1
    (run/'driver-exitcode.txt').write_text(str(status)+'\n')
    return status

def main():
    base=pathlib.Path(__file__).resolve().parent
    default_repo=base.parents[1] if base.parent.name=='verification' else base.parents[2]/'sinka-tibor-code-fabric-vdr-core'
    repo=pathlib.Path(os.environ.get('TDK_PROOF_REPO',str(default_repo))).resolve()
    run=base/'runs'/time.strftime('%Y%m%dT%H%M%SZ',time.gmtime())
    run.mkdir(parents=True,exist_ok=False)
    for script in ['control.py','harness.py','evidence.py','finalize.py']: shutil.copy2(base/script,run/script)
    for doc in ['KICKOFF.md','README.md']:
        if (base/doc).exists(): shutil.copy2(base/doc,run/doc)
    (run/'driver.pid').write_text(str(os.getpid()))
    env={**os.environ,'TDK_PROOF_REPO':str(repo),'TDK_PROOF_PROJECT':'tdkproof-'+run.name.lower(),'GIT_OPTIONAL_LOCKS':'0'}
    def invoke(script,step):
        # Let the shared logger record command/PID/exit even for final aggregation.
        log_script="import sys;sys.path.insert(0,sys.argv[1]);import control;control.run(sys.argv[2],sys.argv[4:],timeout=int(sys.argv[3]))"
        args=[sys.executable,str(run/script)]+([step] if step else [])
        subprocess.run([sys.executable,'-c',log_script,str(run),'phase-'+(step or 'aggregate'),str(PHASE_TIMEOUTS.get(step,600)),*args],env=env,check=True)
    status=run_workflow(run,invoke)
    print('Evidence:',run)
    return status
if __name__=='__main__': sys.exit(main())
