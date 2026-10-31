#!/usr/bin/env python3
"""Build a source snapshot locally, without writing the repository or shared Maven caches."""
import argparse,pathlib,shutil,subprocess,os,json,time,sys
p=argparse.ArgumentParser();p.add_argument('--repo',required=True);p.add_argument('--maven',required=True);args=p.parse_args()
base=pathlib.Path(__file__).resolve().parent
repo=pathlib.Path(args.repo).resolve();maven=pathlib.Path(args.maven).resolve()
out=base/'runs'/('build-'+time.strftime('%Y%m%dT%H%M%SZ',time.gmtime()));out.mkdir(parents=True,exist_ok=False)
project=out/'project';project.mkdir();shutil.copy2(repo/'pom.xml',project/'pom.xml');shutil.copytree(repo/'src',project/'src')
command=[str(maven),'-B','-Dmaven.repo.local='+str(out/'m2'),'package','-DskipTests']
info={'command':command,'cwd':str(project),'start':time.time()}
with (out/'build.log').open('w') as log:
    child=subprocess.Popen(command,cwd=project,stdout=log,stderr=subprocess.STDOUT);info['pid']=child.pid
    (out/'build.pid').write_text(str(child.pid));code=child.wait()
info.update(exitcode=code,end=time.time());(out/'build.json').write_text(json.dumps(info,indent=2))
if code: print('Build failed; see',out/'build.log',file=sys.stderr);sys.exit(code)
assert (project/'target/chaincode.jar').is_file()
print('TDK_PROOF_SNAPSHOT='+str(project));print('MAVEN_EXECUTABLE='+str(maven));print('TDK_PROOF_M2_SEED='+str(out/'m2'))
