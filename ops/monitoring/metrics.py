#!/usr/bin/env python3
"""Root-configured, read-only aggregate exporter. Compatible with Python 3.6."""
import argparse,json,math,os,re,subprocess,threading,time
from http.server import BaseHTTPRequestHandler,HTTPServer
from socketserver import ThreadingMixIn

NAMES={'wallet_active_oldest_seconds','image_pending','image_queue_oldest_seconds',
       'image_unknown_outcomes_24h','image_unknown_estimated_micros_24h',
       'payment_manual','payment_expired_error','payment_refund_review'}
SQL="""
SELECT 'wallet_active_oldest_seconds',COALESCE(MAX(GREATEST(0,TIMESTAMPDIFF(SECOND,COALESCE(g.updated_at,u.updated_at,w.updated_at),CURRENT_TIMESTAMP))),0)
FROM billing_wallet w LEFT JOIN model_usage u ON u.id=w.active_usage_id LEFT JOIN model_call_group g ON g.id=w.active_usage_id WHERE w.active_usage_id IS NOT NULL;
SELECT 'image_pending',COUNT(*) FROM image_job WHERE status IN ('QUEUED','PROCESSING');
SELECT 'image_queue_oldest_seconds',COALESCE(MAX(GREATEST(0,TIMESTAMPDIFF(SECOND,created_at,CURRENT_TIMESTAMP))),0) FROM image_job WHERE status='QUEUED';
SELECT 'image_unknown_outcomes_24h',COUNT(*) FROM usage_reconciliation r JOIN model_usage u ON u.id=r.usage_id WHERE r.action='ABANDONED' AND r.created_at>=TIMESTAMPADD(HOUR,-24,CURRENT_TIMESTAMP) AND u.capability='IMAGE';
SELECT 'image_unknown_estimated_micros_24h',COALESCE(SUM(u.image_price_micros),0) FROM usage_reconciliation r JOIN model_usage u ON u.id=r.usage_id WHERE r.action='ABANDONED' AND r.created_at>=TIMESTAMPADD(HOUR,-24,CURRENT_TIMESTAMP) AND u.capability='IMAGE';
SELECT 'payment_manual',COUNT(*) FROM payment_order WHERE reconcile_state='MANUAL';
SELECT 'payment_expired_error',COUNT(*) FROM payment_order WHERE status='PENDING' AND expires_at<CURRENT_TIMESTAMP AND reconcile_state='ERROR';
SELECT 'payment_refund_review',COUNT(*) FROM payment_refund WHERE status='REVIEW';
"""

def number(value):
    parsed=float(value)
    if not math.isfinite(parsed) or parsed<0:raise ValueError('Invalid metric')
    return str(int(parsed)) if parsed.is_integer() else str(parsed)

def render_sql_rows(text,success):
    lines=[]
    if success:
        try:
            for row in text.splitlines():
                name,value=row.split('\t',1)
                if name in NAMES:lines.append('bobo_'+name+' '+number(value))
        except (ValueError,TypeError):success=False;lines=[]
    return 'bobo_ops_collection_success{source="mysql"} '+('1' if success else '0')+'\n'+'\n'.join(lines)+'\n'

def render_containers(items):
    lines=[]
    for item in items:
        label='{container='+json.dumps(item['name'])+'}'
        lines.extend(['bobo_container_memory_percent'+label+' '+number(item['memoryPercent']),
                      'bobo_container_restart_count'+label+' '+number(item['restarts']),
                      'bobo_container_running'+label+' '+str(int(bool(item['running'])))])
    return '\n'.join(lines)+'\n'

def command(args,**kwargs):
    return subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15,**kwargs)

def collect(config):
    env=os.environ.copy();env['MYSQL_PWD']=config['databasePassword']
    for key in ['mysqlContainer','database','databaseUser']:
        if not re.match(r'^[A-Za-z0-9_.-]+$',config[key]):raise ValueError('Invalid configured identifier')
    p=command(['docker','exec','-i','-e','MYSQL_PWD',config['mysqlContainer'],'mysql','-u'+config['databaseUser'],
               '--batch','--skip-column-names',config['database']],input=SQL.encode(),env=env)
    output=render_sql_rows(p.stdout.decode(errors='replace') if p.returncode==0 else '',p.returncode==0)
    try:
        p=command(['docker','ps','-aq','--filter','label=com.docker.compose.project=agentdemo'])
        ids=p.stdout.decode().split()
        if not ids:raise ValueError('No monitored containers')
        p=command(['docker','inspect']+ids);containers=json.loads(p.stdout.decode())
        p=command(['docker','stats','--no-stream','--format','{{json .}}']+ids)
        memory={s['ID']:s['MemPerc'].rstrip('%') for s in map(json.loads,p.stdout.decode().splitlines())}
        items=[]
        for c in containers:
            key=next((key for key in memory if c['Id'].startswith(key)),None)
            if key is None:raise ValueError('Container sample incomplete')
            items.append({'name':c['Name'].lstrip('/'),'memoryPercent':memory[key],
                          'running':c['State']['Running'],'restarts':c['RestartCount']})
        output+='bobo_ops_collection_success{source="docker"} 1\n'+render_containers(items)
    except Exception:output+='bobo_ops_collection_success{source="docker"} 0\n'
    state=config.get('backupStatusPath','/opt/agentdemo/backup/status.json')
    try:
        with open(state) as source:backup=json.load(source)
        output+='bobo_backup_last_success_timestamp_seconds '+number(backup.get('lastSuccessTimestamp',0))+'\n'
        output+='bobo_backup_last_run_success '+str(int(backup.get('status')=='SUCCESS'))+'\n'
    except (OSError,ValueError):
        output+='bobo_backup_last_success_timestamp_seconds 0\nbobo_backup_last_run_success 0\n'
    return output

class Server(ThreadingMixIn,HTTPServer):daemon_threads=True

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config',required=True);args=parser.parse_args()
    stat=os.stat(args.config)
    if stat.st_mode & 0o077:raise SystemExit('Configuration must be private')
    with open(args.config) as source:config=json.load(source)
    # Restrict the listener to the deployment's loopback/private Docker bridge address.
    import ipaddress
    address=ipaddress.ip_address(config['bind'])
    if not (address.is_loopback or address.is_private):raise SystemExit('Public bind prohibited')
    snapshot={'text':'bobo_ops_collection_success{source="exporter"} 0\n','at':0}
    lock=threading.Lock()
    def update():
        while True:
            try:result=collect(config)
            except Exception:result='bobo_ops_collection_success{source="exporter"} 0\n'
            with lock:snapshot.update(text=result,at=time.time())
            time.sleep(30)
    threading.Thread(target=update,daemon=True).start()
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):
            if self.path not in ('/metrics','/health'):self.send_error(404);return
            with lock:body=snapshot['text'];fresh=time.time()-snapshot['at']<120
            payload=(body if self.path=='/metrics' else 'ok\n').encode()
            self.send_response(200 if fresh else 503);self.send_header('Content-Type','text/plain; version=0.0.4')
            self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
    Server((config['bind'],int(config.get('port',19110))),Handler).serve_forever()

if __name__=='__main__':main()
