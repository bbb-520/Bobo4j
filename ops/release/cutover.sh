set -eu
umask 077
python3 -u - <<'PY'
import json,subprocess,time,urllib.request,urllib.parse,urllib.error,shutil,os,sys
sys.path.insert(0,"/opt/agentdemo/ops/release")
from maintenance import MaintenanceGuard
from verify_manifest import migration_version
from pathlib import Path
root=Path('/opt/agentdemo');r=Path(os.environ['BOBO_RELEASE_DIRECTORY']).resolve();assert r.parent==root/'releases';compose=root/'compose.json'
assert (r/'build.status').read_text().strip()=='VERIFIED'
manifest=json.loads((r/'manifest.json').read_text());before=json.loads(compose.read_text())
shutil.copy2(str(compose),str(r/'compose.cutover.before.json'))
services=['agent-auth-service','agent-media-service','agent-content-service','agent-rag-service','agent-chat-service','agent-orchestrator-service','agent-gateway','agent-web']
staged=json.loads((r/'compose.auth-staged.json').read_text())
staged['services']['nacos']['healthcheck']['test']=['CMD-SHELL','curl -fsS http://127.0.0.1:8848/nacos/v3/admin/core/state/readiness | grep -q ok']
for service in services:
 info=manifest['frontend'] if service=='agent-web' else manifest['services'][service]
 actual=json.loads(subprocess.check_output(['docker','image','inspect',info['image']]))[0]['Id'];assert actual==info['imageId']
 staged['services'][service]['image']=info['image']
# This runner only rolls back to the captured baseline; never reuse with other images.
for service,info in manifest['services'].items():
 assert before['services'][service]['image']==info['baseImage'],'Capture a new matching rollback baseline first'
legacy_media=int(migration_version(r/'baseline/agent-media-service.jar'))<6
# Install a root-owned maintenance switch; stat is readable from nginx's public runtime path.
nginx=Path('/etc/nginx/conf.d/agentdemo.conf');text=nginx.read_text();marker='    # Bobo controlled release maintenance\n    if (-f /run/bobo-release-maintenance) { return 503; }\n'
if marker not in text:
 nginx.write_text(text.replace('    listen 443 ssl;','    listen 443 ssl;\n'+marker,1))
 if subprocess.run(['nginx','-t'],stdout=subprocess.PIPE,stderr=subprocess.PIPE).returncode:
  nginx.write_text(text);raise RuntimeError('Maintenance staging failed')
 subprocess.check_call(['systemctl','reload','nginx'])
old_images={service:json.loads(subprocess.check_output(['docker','inspect','agentdemo-'+service+'-1']))[0]['Image'] for service in ['nacos']+services}
flag=Path('/run/bobo-release-maintenance');guard=MaintenanceGuard(flag,r/'deploy.status');guard.__enter__()
def sql(query):
 p=subprocess.run(['docker','exec','-i','agentdemo-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" -N'],input=query.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 assert p.returncode==0,'Database acceptance query failed';return p.stdout.decode().strip()
def compose_call(*args):subprocess.check_call(['docker','compose','-f',str(compose)]+list(args),cwd=str(root))
def healthy(service,seconds=240):
 end=time.time()+seconds;name='agentdemo-'+service+'-1'
 while time.time()<end:
  c=json.loads(subprocess.check_output(['docker','inspect',name]))[0];assert c['State']['Running'] and not c['State']['OOMKilled'],service+' stopped/OOM'
  if c['State'].get('Health',{}).get('Status')=='healthy':print('Healthy',service,flush=True);return
  time.sleep(3)
 raise RuntimeError(service+' readiness timeout')
changed=False
phase='drain'
def stage(value):
 global phase
 phase=value;(r/'cutover.phase').write_text(value+'\n');print('Phase',value,flush=True)
try:
 stage('draining-existing-work')
 end=time.time()+180
 while time.time()<end:
  if (sql("SELECT COUNT(*) FROM billing_wallet WHERE active_usage_id IS NOT NULL;")=='0'
      and sql("SELECT COUNT(*) FROM image_job WHERE status IN ('QUEUED','PROCESSING');")=='0'
      and sql("SELECT COUNT(*) FROM agent_execution WHERE status IN ('QUEUED','RUNNING','STOPPING');")=='0'):break
  time.sleep(3)
 else:raise RuntimeError('Active work did not drain; release deferred')
 # Drain/stop all old writers before media V6 and the Nacos authentication switch.
 guard.mark_changed();changed=True
 compose_call('stop','-t','45',*reversed(services))
 stage('old-writers-stopped')
 # A prior rollback may have temporarily restored the old implicit admission index.
 if sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history_media WHERE success=1;")=='6' and sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='image_job' AND column_name='active_slot';")=='1':
  sql("DROP INDEX uk_image_job_active_owner ON image_job; ALTER TABLE image_job DROP COLUMN active_slot;")
 with (r/'mysql.quiesced.sql').open('wb') as out:
  p=subprocess.run(['docker','exec','agentdemo-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --events --triggers --set-gtid-purged=OFF --no-tablespaces --databases "$MYSQL_DATABASE"'],stdout=out,stderr=subprocess.PIPE);assert p.returncode==0
 compose.write_text(json.dumps(staged,indent=2)+'\n');compose.chmod(0o600);compose_call('config','--quiet')
 stage('nacos-authentication-switch');compose_call('up','-d','--no-deps','nacos');healthy('nacos')
 access=json.loads((root/'ops/private/nacos-access.json').read_text())
 def api(path,data=None,method='GET',token=None):
  headers={'Authorization':'Bearer '+token,'accessToken':token} if token else {}
  req=urllib.request.Request('http://127.0.0.1:8848'+path,data=urllib.parse.urlencode(data).encode() if data is not None else None,method=method,headers=headers)
  response=urllib.request.urlopen(req,timeout=15);body=response.read();return json.loads(body) if body else {'code':0}
 login=api('/nacos/v3/auth/user/login',{'username':access['serviceUsername'],'password':access['servicePassword']},'POST');token=login['accessToken']
 # Verify both configuration/discovery scope before starting applications.
 for path in ['/nacos/v3/client/ns/instance/list?serviceName=agent-auth-service&groupName=AGENT_GROUP','/nacos/v3/client/cs/config?dataId=agent-auth-service.yml&groupName=AGENT_GROUP']:
  try:
   result=api(path,token=token);assert result.get('code') in (0,20004),'Nacos scoped API refused request'
  except urllib.error.HTTPError as error:assert error.code==404,'Service Nacos scope authentication failed'
 # Legacy3.1 resolves PUT identity from query parameters; do not emit URL or dependency body.
 if access.get('adminRotationPending'):
  old=before['services']['agent-auth-service']['environment']
  admin_token=api('/nacos/v3/auth/user/login',{'username':'nacos','password':old['NACOS_PASSWORD']},'POST')['accessToken']
  params=urllib.parse.urlencode({'username':'nacos','newPassword':access['adminPassword'],'accessToken':admin_token})
  api('/nacos/v3/auth/user?'+params,{},'PUT',admin_token)
  api('/nacos/v3/auth/user/login',{'username':'nacos','password':access['adminPassword']},'POST')
  access['adminRotationPending']=False;(root/'ops/private/nacos-access.json').write_text(json.dumps(access))
 print('Authenticated scoped Nacos clients and administrator verified',flush=True)
 stage('auth-start');compose_call('up','-d','--no-deps','agent-auth-service');healthy('agent-auth-service')
 group=services[1:-1];stage('backend-group-start');compose_call('up','-d','--no-deps',*group)
 for service in group:healthy(service,420)
 stage('frontend-start');compose_call('up','-d','--no-deps','agent-web');healthy('agent-web')
 for service in services:
  info=manifest['frontend'] if service=='agent-web' else manifest['services'][service]
  actual=json.loads(subprocess.check_output(['docker','inspect','agentdemo-'+service+'-1']))[0]['Image'];assert actual==info['imageId']
 stage('artifact-and-owned-schema-acceptance');rows=[]
 for service,info in manifest['services'].items():
  expected=info.get('migrationVersion')
  if expected is None:continue
  owner=service[len('agent-'):].replace('-service','')
  row=sql("SELECT '"+owner+"',version,success FROM flyway_schema_history_"+owner+" ORDER BY installed_rank DESC LIMIT 1;")
  assert row==owner+'\t'+expected+'\t1','Owned migration does not match tested artifact: '+owner
  rows.append(row)
 migrations='\n'.join(rows)
 manifest['appliedMigrations']=migrations.splitlines();manifest['deployedAt']=time.time();(r/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
 guard.accept('COMPLETE');print('Unified release complete; migration versions',migrations.replace('\n','; '),flush=True)
except Exception as error:
 print('Cutover failed at '+phase+' ('+type(error).__name__+'); restoring prior application configuration',flush=True)
 if changed:
  compose_call('stop','-t','30',*reversed(services))
  # No new external traffic has been admitted. Preserve audit data, restore the old admission invariant.
  if legacy_media and sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='image_job' AND column_name='active_slot';")=='0':
   assert sql("SELECT COUNT(*) FROM (SELECT user_id FROM image_job WHERE status IN ('QUEUED','PROCESSING') GROUP BY tenant_id,user_id HAVING COUNT(*)>1) x;")=='0'
   sql("ALTER TABLE image_job ADD COLUMN active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('QUEUED','PROCESSING') THEN 1 ELSE NULL END) VIRTUAL; CREATE UNIQUE INDEX uk_image_job_active_owner ON image_job(tenant_id,user_id,active_slot);")
  shutil.copy2(str(r/'compose.cutover.before.json'),str(compose));compose_call('up','-d','--no-deps','nacos');healthy('nacos')
  for service in services:compose_call('up','-d','--no-deps',service);healthy(service)
 for service,expected_image in old_images.items():
   actual=json.loads(subprocess.check_output(['docker','inspect','agentdemo-'+service+'-1']))[0]['Image']
   assert actual==expected_image,'Rollback image mismatch: '+service
 guard.accept('ROLLED_BACK');raise RuntimeError('Release not accepted; previous applications restored') from None
finally:
 guard.__exit__(*sys.exc_info())
PY
