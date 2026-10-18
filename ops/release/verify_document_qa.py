"""Private live QA runner: server-side, exact accepted release, persistent receipts."""
import subprocess,json,time,uuid,hashlib,hmac,base64,urllib.request,urllib.error,re,os,sys,shlex
from pathlib import Path
r=Path(sys.argv[1])
assert r.parent==Path('/opt/agentdemo/releases') and r.is_dir()
assert (r/'deploy.status').read_text().strip()=='COMPLETE'
def sql(q):
 p=subprocess.run(['docker','exec','-i','agentdemo-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot "$MYSQL_DATABASE" -N -B --raw'],input=q.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 assert p.returncode==0,'Acceptance SQL failed'
 return p.stdout.decode().strip()
def quote(v):return 'CONVERT(0x'+str(v).encode().hex()+' USING utf8mb4)'
resume='6125f2cd-1792-4dc4-b1fd-e17fd887aaf8'
owner=json.loads(sql("SELECT JSON_OBJECT('user',user_id,'tenant',tenant_id,'version',index_version) FROM rag_document WHERE document_id='"+resume+"' AND status='READY';"))
c=json.loads(subprocess.check_output(['docker','inspect','agentdemo-agent-rag-service-1']))[0]
env=dict(e.split('=',1) for e in c['Config']['Env'])
storage=Path(os.path.normpath(str(Path(c['Config'].get('WorkingDir') or '/')/env.get('RAG_STORAGE_ROOT','./data/rag'))))
address=next(n['IPAddress'] for n in c['NetworkSettings']['Networks'].values() if n.get('IPAddress'))
base='http://'+address+':'+env.get('SERVER_PORT','18086')
kid=env.get('INTERNAL_PRINCIPAL_ACTIVE_KEY_ID','current');keys=dict(k.split('=',1) for k in env['INTERNAL_PRINCIPAL_SECRETS'].split(','))
def b64(v):return base64.urlsafe_b64encode(v).decode().rstrip('=')
def invoke(body):
 path='/internal/rag/answer';now=int(time.time())
 payload='|'.join(['v2',kid,'agent-orchestrator-service',owner['user'],owner['tenant'],'agent-rag-service','POST '+path,str(now),str(now+60)])
 token=b64(payload.encode())+'.'+b64(hmac.new(base64.b64decode(keys[kid]),payload.encode(),hashlib.sha256).digest())
 req=urllib.request.Request(base+path,data=json.dumps(body).encode(),headers={'Content-Type':'application/json','X-Internal-Principal':token})
 try:
  with urllib.request.urlopen(req,timeout=240) as reply:return json.load(reply)
 except urllib.error.HTTPError as error:
  print('HTTP_ERROR',error.code,error.read().decode()[:350],flush=True);raise
state=r/'acceptance.requests.json'
if state.exists():data=json.loads(state.read_text())
else:
 data={'documents':{'resume':{'id':resume,'version':owner['version']}},'conversations':{},'requests':{},'results':{}}
 state.write_text(json.dumps(data,ensure_ascii=False,indent=2));state.chmod(0o600)
if data.get('fixturesCleaned'):
 assert (r/'acceptance.status').read_text().strip()=='COMPLETE'
 print('Existing completed acceptance preserved; use a new release directory for another run.')
 sys.exit(0)
def save():state.write_text(json.dumps(data,ensure_ascii=False,indent=2))
# Disposable synthetic ingestion jobs use the same durable registration shape as RagRepository.register.
for kind in ['long','table']:
 if all(key in data['results'] for key in ['resume','followup','long-document','table','no-evidence']):break
 if kind not in data['documents']:
  text=('\n\n'.join('第%d节：AX%02d 技术方案。系统采用 Java 21 与 Spring Boot，文档索引使用语义切片和向量检索。配置参数必须按方案编号查找，不能混淆不同方案。默认重试次数为%d次，超时时间为%d秒。'%(i,i,i%4,10+i) for i in range(1,65))
        if kind=='long' else '方案参数表\n\n| 方案 | 重试次数 | 超时时间 |\n| --- | --- | --- |\n| 甲方案 | 2次 | 30秒 |\n| 乙方案 | 3次 | 45秒 |\n\n文档仅记录技术参数，不包含人员或联系方式。')
  doc=str(uuid.uuid4()); request='p0p1-'+kind+'-'+str(uuid.uuid4());inside=str(storage/'original'/('p0p1-'+doc+'.txt'))
  subprocess.check_call(['docker','exec','agentdemo-agent-rag-service-1','mkdir','-p','--',str(storage/'original')])
  p=subprocess.run(['docker','exec','-i','agentdemo-agent-rag-service-1','sh','-c','cat > '+shlex.quote(inside)],input=text.encode());assert p.returncode==0
  sql('START TRANSACTION; INSERT INTO rag_document(document_id,tenant_id,user_id,request_id,filename,content_hash,storage_path,status,index_status,summary_status) VALUES ('+','.join(quote(v) for v in [doc,owner['tenant'],owner['user'],request,'P0P1-'+kind+'.txt',hashlib.sha256(text.encode()).hexdigest(),inside])+",'UPLOADED','PENDING','PENDING'); INSERT INTO rag_job(document_id,tenant_id,user_id,index_version,stage) VALUES ("+','.join(quote(v) for v in [doc,owner['tenant'],owner['user']])+",1,'UPLOADED'); COMMIT;")
  data['documents'][kind]={'id':doc,'version':1,'fixturePath':inside};save()
 for _ in range(160):
  status=sql('SELECT CONCAT_WS(\'|\',status,index_status,summary_status,COALESCE(error,\'\'),chunk_count) FROM rag_document WHERE document_id='+quote(data['documents'][kind]['id'])+';')
  if status.startswith('READY|READY|READY|'):print('INGESTION_READY',kind,status,flush=True);break
  if status.startswith(('FAILED|','WAITING_FOR_')):raise RuntimeError(kind+' ingestion failed: '+status)
  time.sleep(3)
 else:raise RuntimeError(kind+' ingestion timeout')
cases=json.loads((r/'document-qa.json').read_text())['cases']
for case in cases:
 name=case['id']
 if name=='replay':
  original=data['requests'][case['replay']]
  before=sql('SELECT COUNT(*) FROM rag_model_call WHERE composite_id='+quote(original['callId'])+';')
  answer=invoke(original)
  assert answer==data['results'][case['replay']],'Replay changed the receipt'
  assert sql('SELECT COUNT(*) FROM rag_model_call WHERE composite_id='+quote(original['callId'])+';')==before,'Replay made another paid call'
  print('CASE_PASS',name,flush=True);continue
 if name not in data['requests']:
  conversationKey=case.get('conversation',name)
  if conversationKey not in data['conversations']:
   conversation=str(uuid.uuid4());doc=data['documents'][case['document']]
   sql('INSERT INTO rag_conversation(conversation_id,tenant_id,user_id,versions_json) VALUES ('+','.join(quote(v) for v in [conversation,owner['tenant'],owner['user'],json.dumps({doc['id']:doc['version']})])+');')
   data['conversations'][conversationKey]=conversation
  data['requests'][name]={'callId':'p0p1-'+name+'-'+str(uuid.uuid4()),'conversationId':data['conversations'][conversationKey],'question':case['question'],'tokenBudget':24000};save()
 answer=data['results'].get(name) or invoke(data['requests'][name]);data['results'][name]=answer;save()
 if case['expected']=='PASS':assert answer['evaluation']=='PASS' and answer['sources'],name+' grounding did not pass: '+answer['evaluation']
 else:
  assert not re.search(r'1[3-9][0-9]{9}',answer['text']),'Invented phone number'
  assert any(word in answer['text'] for word in ['没有','未','不足','不包含','无法']),'Missing evidence was not acknowledged'
 if name=='long-document':
  assert re.search(r'(?<![0-9])52(?![0-9])',answer['text']) and re.search(r'(?<![0-9])2(?![0-9])',answer['text']),'AX42 numeric values changed'
 if name=='table':
  assert re.search(r'甲[^\n]*2[^\n]*30',answer['text']) and re.search(r'乙[^\n]*3[^\n]*45',answer['text']),'Table row/value association changed'
 assert answer.get('unknownReservedTokens',0)==0,'Unreconciled usage'
 print('CASE_PASS',name,'evaluation',answer['evaluation'],'sources',len(answer['sources']),'characters',len(answer['text']),flush=True)
for kind in ['long','table']:
 doc=data['documents'][kind]
 assert re.fullmatch('p0p1-[0-9a-f-]+[.]txt',Path(doc['fixturePath']).name)
 predicate='document_id='+quote(doc['id'])+' AND tenant_id='+quote(owner['tenant'])+' AND user_id='+quote(owner['user'])
 # Repair the first runner's /tmp fixture location so the production cleanup path guard can accept it.
 if Path(doc['fixturePath']).parent==Path('/tmp'):
  replacement=str(storage/'original'/Path(doc['fixturePath']).name)
  subprocess.check_call(['docker','exec','agentdemo-agent-rag-service-1','mkdir','-p','--',str(storage/'original')])
  subprocess.check_call(['docker','exec','agentdemo-agent-rag-service-1','mv','--',doc['fixturePath'],replacement])
  sql('UPDATE rag_document SET storage_path='+quote(replacement)+' WHERE '+predicate+' AND filename='+quote('P0P1-'+kind+'.txt')+';')
  doc['fixturePath']=replacement;save()
 assert Path(doc['fixturePath']).parent==storage/'original'
 sql("START TRANSACTION; UPDATE rag_document SET deleted=TRUE,status='CANCELLED',updated_at=CURRENT_TIMESTAMP(6) WHERE "+predicate+" AND filename="+quote('P0P1-'+kind+'.txt')+"; UPDATE rag_job SET stage='CLEANUP',fence=fence+1,lease_until=NULL,available_at=CURRENT_TIMESTAMP(6) WHERE "+predicate+" AND EXISTS(SELECT 1 FROM rag_document d WHERE d.document_id=rag_job.document_id AND d.deleted=TRUE); COMMIT;")
 for _ in range(60):
  if sql('SELECT stage FROM rag_job WHERE '+predicate+';')=='CANCELLED' and sql('SELECT COUNT(*) FROM rag_chunk WHERE '+predicate+';')=='0':break
  time.sleep(2)
 else:raise RuntimeError('Synthetic fixture cleanup timeout')
 subprocess.check_call(['docker','exec','agentdemo-agent-rag-service-1','rm','-f','--',doc['fixturePath']])
 print('FIXTURE_CLEANED',kind,flush=True)
data['fixturesCleaned']=True;save()
(r/'acceptance.status').write_text('COMPLETE\n')
print('LIVE_ACCEPTANCE_COMPLETE',flush=True)
