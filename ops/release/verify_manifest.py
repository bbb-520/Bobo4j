#!/usr/bin/env python3
"""Verify tested release payloads and source/runtime identities before image builds."""
import argparse,hashlib,json,re
from pathlib import Path
SERVICES={'agent-gateway','agent-auth-service','agent-media-service','agent-content-service',
          'agent-orchestrator-service','agent-chat-service','agent-rag-service'}
def digest(path):
 result=hashlib.sha256()
 with Path(path).open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):result.update(block)
 return result.hexdigest()
def require(value,message):
 if not value:raise ValueError(message)
def verify(root,manifest,backend,frontend):
 root=Path(root)
 require(re.fullmatch('[0-9a-f]{40}',backend) and re.fullmatch('[0-9a-f]{40}',frontend),'Expected full source revisions required')
 require(manifest.get('source',{}).get('backendCommit')==backend and manifest.get('source',{}).get('frontendCommit')==frontend,'Source revision mismatch')
 require(set(manifest.get('services',{}))==SERVICES,'Incomplete or unexpected service set')
 for name,info in list(manifest['services'].items())+[('frontend',manifest.get('frontend',{}))]:
  require(re.fullmatch('sha256:[0-9a-f]{64}',info.get('runtimeId','')),'Immutable runtime identity required')
  expected=info.get('sha256','');require(re.fullmatch('[0-9a-f]{64}',expected),'Artifact SHA256 required')
  path=root/'web.tar.gz' if name=='frontend' else root/name/'app.jar'
  require(path.is_file() and not path.is_symlink() and digest(path)==expected,'Artifact missing or changed: '+name)
def main():
 parser=argparse.ArgumentParser();parser.add_argument('directory');parser.add_argument('--expected-backend',required=True);parser.add_argument('--expected-frontend',required=True);args=parser.parse_args()
 with (Path(args.directory)/'manifest.json').open() as source:manifest=json.load(source)
 verify(args.directory,manifest,args.expected_backend,args.expected_frontend)
 print('Release source, immutable runtimes and all eight artifact digests verified')
if __name__=='__main__':main()
