#!/usr/bin/env python3
"""Exercise orchestration failure propagation without Docker or another network run."""
import pathlib,tempfile,json,subprocess,os,sys,time
import reproduce
base=pathlib.Path(__file__).resolve().parent
cases=[]
for fail,emit,expected in [(None,True,0),('config',True,1),('tests',True,1),('manifest',True,1),('capture',True,1),('cleanup',True,1),('sanitize',True,1),('aggregate',True,1),(None,False,1),(None,'invalid',1)]:
    with tempfile.TemporaryDirectory(prefix='wrapper-test-',dir=base) as tmp:
        run=pathlib.Path(tmp);calls=[]
        def invoke(script,step):
            key=step or 'aggregate';calls.append(key)
            if key=='up': (run/'owned.json').write_text('{}')
            if key==fail: raise RuntimeError('synthetic failure: '+key)
            if key=='aggregate' and emit:
                (run/'results.json').write_text(json.dumps({'validated':emit is True}));(run/'REPORT.md').write_text('Synthetic validated result')
        result=reproduce.run_workflow(run,invoke)
        assert result==expected,(fail,emit,result)
        assert 'sanitize' in calls
        if fail!='config': assert all(x in calls for x in ['capture','cleanup'])
        else: assert 'capture' not in calls and 'cleanup' not in calls
        assert ('aggregate' in calls)==(fail in [None,'aggregate'])
        assert int((run/'driver-exitcode.txt').read_text())==expected
        cases.append({'failure_phase':fail,'aggregate_emits_artifacts':emit,'exitcode':result,'phases':calls})
# Environment overrides are accepted without reading any original build pointer.
probe="import control,harness,json;print(json.dumps([str(control.BUILD),str(control.snapshot_project()),str(harness.maven_executable())]))"
expected_paths=[str(base/'synthetic-build-override'),str(base/'synthetic-snapshot-override'),str(base/'synthetic-maven-override')]
env={**os.environ,**dict(zip(['TDK_PROOF_BUILD','TDK_PROOF_SNAPSHOT','MAVEN_EXECUTABLE'],expected_paths))}
child=subprocess.run([sys.executable,'-c',probe],cwd=base,env=env,text=True,stdout=subprocess.PIPE,check=True)
assert json.loads(child.stdout)==expected_paths
# Exercise real nested processes: a timed-out phase and a timed-out inner command
# must both stop a grandchild that ignores SIGTERM before cleanup can begin.
worker="import os,pathlib,signal,sys,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);pathlib.Path(sys.argv[1]).write_text(str(os.getpid()));time.sleep(60)"
leaf="import subprocess,sys,time;subprocess.Popen([sys.executable,'-c',"+repr(worker)+",sys.argv[1]]);time.sleep(60)"
nested="import control,pathlib,sys;control.ROOT=pathlib.Path(sys.argv[1]);control.run('nested',[sys.executable,'-c',sys.argv[2],sys.argv[3]],cwd=control.ROOT,timeout=float(sys.argv[4]))"
driver="""import control,pathlib,sys
control.ROOT=pathlib.Path(sys.argv[1])
try:
    control.run('outer',[sys.executable,'-c',sys.argv[2],sys.argv[1],sys.argv[3],sys.argv[4],sys.argv[5]],cwd=control.ROOT,timeout=float(sys.argv[6]))
except RuntimeError:
    pass
else:
    raise RuntimeError('Synthetic timeout did not fail')
"""
process_cases=[]
for name,inner_timeout,outer_timeout in [('phase_timeout',10,1),('nested_timeout',0.5,5)]:
    with tempfile.TemporaryDirectory(prefix='process-test-',dir=base) as tmp:
        run=pathlib.Path(tmp);(run/'logs').mkdir();pidfile=run/'grandchild.pid'
        env={**os.environ,'PYTHONPATH':str(base)}
        env.pop('TDK_PROOF_MANAGED_PROCESS_GROUP',None)
        subprocess.run([sys.executable,'-c',driver,str(run),nested,leaf,str(pidfile),str(inner_timeout),str(outer_timeout)],env=env,check=True,timeout=10,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        pid=int(pidfile.read_text())
        def still_running():
            try: return pathlib.Path(f'/proc/{pid}/stat').read_text().split(') ',1)[1][0] not in ['Z','X']
            except FileNotFoundError: return False
        deadline=time.monotonic()+2
        while still_running() and time.monotonic()<deadline: time.sleep(0.01)
        assert not still_running(),f'{name} left grandchild {pid} running'
        outer=json.loads((run/'logs/outer.json').read_text());inner=json.loads((run/'logs/nested.json').read_text())
        assert outer['owns_process_group'] and not inner['owns_process_group']
        assert outer['process_group']==inner['process_group']
        assert outer['exitcode']==(124 if name=='phase_timeout' else 1)
        if name=='nested_timeout': assert inner['exitcode']==124
        process_cases.append({'case':name,'grandchild_stopped':True,'outer_exitcode':outer['exitcode']})
assert reproduce.PHASE_TIMEOUTS['tests']==1800
(base/'wrapper-tests.json').write_text(json.dumps({'passed':True,'cases':cases,'build_snapshot_maven_overrides':True,'nested_process_timeouts':process_cases,'tests_phase_timeout_seconds':reproduce.PHASE_TIMEOUTS['tests']},indent=2))
print('PASS: 10 orchestration cases; build/snapshot/Maven overrides; 2 nested-process timeout cases; 1800-second tests-phase budget. No Docker used.')
