import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'release'))
import deploy_phase1 as release_module


class PublicDocsRollbackTest(unittest.TestCase):
    def test_failed_second_public_docs_release_restores_captured_image_and_keeps_old_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'site'
            release = root / 'releases' / '20261016-mcp-phase2-v2'
            release.mkdir(parents=True)
            jar = release / 'app.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/classes/application.yml', 'MCP_ENABLED:false\ntoolcallback.enabled: false')
                archive.writestr('BOOT-INF/classes/db/migration/V1__baseline.sql', 'select 1;\n')
            manifest = dict(publicSnapshotVerified=True, mcpTenants=['local'],
                            jarSha256=release_module.hashlib.sha256(jar.read_bytes()).hexdigest())
            (release / 'manifest.json').write_text(json.dumps(manifest))
            old_tag = 'bobo-mcp20261016/agent-orchestrator-service:phase2'
            images = {old_tag: 'sha256:old-orchestrator'}
            before = {'services': {s: {'image': old_tag if s == release_module.SERVICE else s + ':old',
                                     'environment': {'MCP_ENABLED': 'true'}} for s in release_module.SERVICES}}
            compose = root / 'compose.json'
            compose.write_text(json.dumps(before))
            switches = [0]
            accepted = []

            class Guard:
                def __init__(self, *args): pass
                def __enter__(self): return self
                def mark_changed(self): pass
                def accept(self, value): accepted.append(value)
                def __exit__(self, *args): return False

            def inspect(service):
                image = json.loads(compose.read_text())['services'][service]['image']
                return {'Image': images[image] if service == release_module.SERVICE else 'sha256:' + service,
                        'Config': {'Env': ['MCP_ENABLED=true']}, 'NetworkSettings': {'Networks': {'private': {'IPAddress': '127.0.0.1'}}}}

            def run(*args):
                if args[:3] == ('docker', 'image', 'tag'):
                    images[args[4]] = args[3]
                elif args[:3] == ('docker', 'image', 'inspect'):
                    if args[3] not in images:
                        raise subprocess.CalledProcessError(1, args)
                    return json.dumps([{'Id': images[args[3]]}]).encode()
                elif args[:2] == ('docker', 'cp'):
                    Path(args[3]).write_bytes(jar.read_bytes())
                elif args[:2] == ('docker', 'build'):
                    images[args[args.index('-t') + 1]] = 'sha256:new-orchestrator'
                elif args[:2] == ('docker', 'compose') and 'up' in args:
                    switches[0] += 1
                return b''

            def health(service, timeout=240):
                if switches[0] == 1:
                    raise RuntimeError('simulated startup failure')

            with patch.object(release_module, 'ROOT', root), patch.object(release_module, 'run', run), \
                    patch.object(release_module, 'inspect', inspect), patch.object(release_module, 'healthy', health), \
                    patch.object(release_module, 'sql', return_value='0'), patch.object(release_module, 'MaintenanceGuard', Guard), \
                    patch('live_public_docs.verify_live'), patch.object(sys, 'argv', ['deploy', str(release)]):
                with self.assertRaisesRegex(RuntimeError, 'simulated startup failure'):
                    release_module.main(public_docs=True)
            self.assertEqual(accepted, ['ROLLED_BACK'])
            self.assertEqual(inspect(release_module.SERVICE)['Image'], 'sha256:old-orchestrator')
            self.assertEqual(images[old_tag], 'sha256:old-orchestrator')
            self.assertIn('bobo-mcp20261016/agent-orchestrator-service:' + release.name, images)


if __name__ == '__main__':
    unittest.main()
