#!/usr/bin/env python3
"""Deploy one verified orchestrator artifact, with MCP disabled and rollback guarded."""
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path('/opt/agentdemo')
SERVICE = 'agent-orchestrator-service'
SERVICES = ['agent-auth-service', 'agent-media-service', 'agent-content-service',
            'agent-rag-service', 'agent-chat-service', SERVICE, 'agent-gateway', 'agent-web']
sys.path.insert(0, str(ROOT / 'ops/release'))
from maintenance import MaintenanceGuard


def run(*args):
    return subprocess.check_output(list(args), stderr=subprocess.PIPE)


def inspect(service):
    return json.loads(run('docker', 'inspect', 'agentdemo-' + service + '-1'))[0]


def migrations(jar):
    with zipfile.ZipFile(str(jar)) as archive:
        return {name: hashlib.sha256(archive.read(name).replace(b'\r\n', b'\n')
                                    if name.endswith('.sql') else archive.read(name)).hexdigest()
                for name in archive.namelist()
                if name.startswith(('BOOT-INF/classes/db/migration/',
                                    'BOOT-INF/classes/db/orchestrator/'))
                and not name.endswith('/')}


def healthy(service, timeout=240):
    deadline = time.time() + timeout
    while time.time() < deadline:
        state = inspect(service)['State']
        if state.get('Health', {}).get('Status') == 'healthy' and state['Running']:
            return
        if state.get('OOMKilled'):
            raise RuntimeError('Container OOM: ' + service)
        time.sleep(3)
    raise RuntimeError('Health timeout: ' + service)


def sql(query):
    process = subprocess.run(['docker', 'exec', '-i', 'agentdemo-mysql-1', 'sh', '-c',
                              'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" -N'],
                             input=query.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if process.returncode:
        raise RuntimeError('Drain query failed')
    return process.stdout.decode().strip()


def main(public_docs=False):
    release = Path(sys.argv[1]).resolve()
    assert release.parent == ROOT / 'releases'
    assert re.fullmatch(r'[a-z0-9][a-z0-9_.-]{0,100}', release.name), 'Invalid release name'
    assert not (release / 'deploy.status').exists(), 'Use a fresh release directory'
    assert not Path('/run/bobo-release-maintenance').exists(), 'Another maintenance is active'
    manifest = json.loads((release / 'manifest.json').read_text())
    assert manifest['publicSnapshotVerified'] is True
    tenants = manifest.get('mcpTenants', []) if public_docs else []
    if public_docs:
        assert 0 < len(tenants) <= 32 and all(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', t) for t in tenants)
    jar = release / 'app.jar'
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == manifest['jarSha256']
    compose = ROOT / 'compose.json'
    before = json.loads(compose.read_text())
    baseline = {service: inspect(service)['Image'] for service in SERVICES}
    for service in SERVICES:
        healthy(service, 15)
    shutil.copy2(str(compose), str(release / 'compose.before.json'))
    (release / 'compose.before.json').chmod(0o600)
    run('docker', 'cp', 'agentdemo-' + SERVICE + '-1:/app/app.jar', str(release / 'before.jar'))
    old_migrations, new_migrations = migrations(release / 'before.jar'), migrations(jar)
    assert old_migrations and old_migrations == new_migrations, 'Migration mismatch'
    with zipfile.ZipFile(str(jar)) as archive:
        config = archive.read('BOOT-INF/classes/application.yml').decode()
        assert 'MCP_ENABLED:false' in config and 'toolcallback.enabled: false' in config
    # BuildKit interprets FROM sha256:<image-id> as a registry tag. Pin a local tag
    # to the captured image ID, verify it, and build without a registry pull.
    base_tag = 'bobo-mcp20261016/orchestrator-base:' + release.name + '-rollback'
    run('docker', 'image', 'tag', baseline[SERVICE], base_tag)
    assert json.loads(run('docker', 'image', 'inspect', base_tag))[0]['Id'] == baseline[SERVICE]
    dockerfile = 'FROM ' + base_tag + '\nCOPY --chown=app:app app.jar /app/app.jar\n'
    (release / 'Dockerfile').write_text(dockerfile)
    (release / '.dockerignore').write_text('*\n!Dockerfile\n!app.jar\n')
    image = 'bobo-mcp20261016/agent-orchestrator-service:' + release.name
    try:
        run('docker', 'image', 'inspect', image)
    except subprocess.CalledProcessError:
        pass
    else:
        raise RuntimeError('Release image already exists; use a fresh release directory')
    run('docker', 'build', '--pull=false', '-t', image, str(release))
    image_id = json.loads(run('docker', 'image', 'inspect', image))[0]['Id']
    manifest.update(beforeImageIds=baseline, beforeImage=before['services'][SERVICE]['image'],
                    image=image, imageId=image_id, migrations=old_migrations,
                    mcpEnabled=public_docs)
    (release / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('Artifact and unchanged migrations verified; new image built', flush=True)
    if public_docs:
        from live_public_docs import verify_live
        verify_live(release, image, next(iter(inspect(SERVICE)['NetworkSettings']['Networks'])))

    def compose_up():
        run('docker', 'compose', '-f', str(compose), 'up', '-d', '--no-deps', SERVICE)

    with MaintenanceGuard(Path('/run/bobo-release-maintenance'), release / 'deploy.status') as guard:
        changed = False
        try:
            deadline = time.time() + 180
            while time.time() < deadline:
                if (sql("SELECT COUNT(*) FROM billing_wallet WHERE active_usage_id IS NOT NULL;") == '0'
                    and sql("SELECT COUNT(*) FROM image_job WHERE status IN ('QUEUED','PROCESSING');") == '0'
                    and sql("SELECT COUNT(*) FROM agent_execution WHERE status IN ('QUEUED','RUNNING','STOPPING');") == '0'):
                    break
                time.sleep(3)
            else:
                raise RuntimeError('Active work did not drain')
            staged = json.loads(compose.read_text())
            assert staged == before, 'Configuration changed during preflight'
            staged['services'][SERVICE]['image'] = image
            environment = staged['services'][SERVICE].setdefault('environment', {})
            assert isinstance(environment, dict)
            environment['MCP_ENABLED'] = 'true' if public_docs else 'false'
            if public_docs:
                environment['MCP_PUBLIC_DOCS_ENABLED'] = 'true'
                environment['MCP_PUBLIC_DOCS_TENANTS'] = ','.join(tenants)
                environment['MCP_REQUEST_TIMEOUT'] = '30s'
            guard.mark_changed()
            changed = True
            compose.write_text(json.dumps(staged, indent=2) + '\n')
            compose.chmod(0o600)
            run('docker', 'compose', '-f', str(compose), 'config', '--quiet')
            compose_up()
            healthy(SERVICE)
            for service in SERVICES:
                assert inspect(service)['Image'] == (image_id if service == SERVICE else baseline[service])
                healthy(service, 15)
            deployed = inspect(SERVICE)
            assert ('MCP_ENABLED=true' if public_docs else 'MCP_ENABLED=false') in deployed['Config']['Env']
            if public_docs:
                from live_public_docs import approved_counts
                approved_counts(deployed)
            run('docker', 'cp', 'agentdemo-' + SERVICE + '-1:/app/app.jar', str(release / 'running.jar'))
            assert hashlib.sha256((release / 'running.jar').read_bytes()).hexdigest() == manifest['jarSha256']
            manifest['deployedAt'] = time.strftime('%Y-%m-%dT%H:%M:%S%z')
            manifest['allEightHealthy'] = True
            manifest['otherImagesUnchanged'] = True
            (release / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
            guard.accept('COMPLETE')
            print('COMPLETE: all eight healthy, other images unchanged, MCP ' + ('public-docs enabled' if public_docs else 'disabled'), flush=True)
        except Exception:
            if changed:
                current = json.loads(compose.read_text())
                restored = dict(before['services'][SERVICE])
                run('docker', 'image', 'tag', baseline[SERVICE], base_tag)
                assert json.loads(run('docker', 'image', 'inspect', base_tag))[0]['Id'] == baseline[SERVICE]
                restored['image'] = base_tag
                current['services'][SERVICE] = restored
                compose.write_text(json.dumps(current, indent=2) + '\n')
                compose.chmod(0o600)
                compose_up()
                healthy(SERVICE)
                for service in SERVICES:
                    assert inspect(service)['Image'] == baseline[service]
                    healthy(service, 15)
                guard.accept('ROLLED_BACK')
            raise


if __name__ == '__main__':
    main()
