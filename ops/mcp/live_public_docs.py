"""Private Java SDK live verification using public queries and an isolated H2 ledger."""
import json
import io
import re
import subprocess
import time
import urllib.request
import zipfile
from pathlib import Path


def verify_module_jar(jar):
    with zipfile.ZipFile(str(jar)) as app:
        names=app.namelist()
        modules=[name for name in names if name.startswith('BOOT-INF/lib/agent-mcp-') and name.endswith('.jar')]
        assert len(modules)==1, 'Exactly one MCP library must be nested in the application'
        assert 'BOOT-INF/classes/com/bbb/exercise/agentdemo/orchestrator/mcp/McpConnectionManager.class' not in names, 'Stale MCP implementation in orchestrator'
        assert 'BOOT-INF/classes/com/bbb/exercise/agentdemo/orchestrator/execution/ToolSchema.class' not in names, 'Stale schema implementation in orchestrator'
        with zipfile.ZipFile(io.BytesIO(app.read(modules[0]))) as library:
            assert 'com/bbb/exercise/agentdemo/mcp/McpConnectionManager.class' in library.namelist(), 'MCP must be a plain library JAR'
            assert 'mcp/public-docs/wiki-question.input.json' in library.namelist(), 'Reviewed definitions must be packaged with MCP'


def extract_selected(archive, root, accept):
    root = root.resolve()
    with zipfile.ZipFile(str(archive)) as files:
        for name in files.namelist():
            if name.endswith('/') or not accept(name):
                continue
            target = (root / name).resolve()
            assert str(target).startswith(str(root) + '/'), 'Archive path outside release'
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(files.read(name))


def verify_live(release, image, network):
    verify_module_jar(release / 'app.jar')
    live = release / 'live'
    extract_selected(release / 'app.jar', live,
                     lambda n: n.startswith('BOOT-INF/classes/') or n.startswith('BOOT-INF/lib/'))
    extract_selected(release / 'live-check.zip', live,
                     lambda n: n.startswith('classes/') or n.startswith('lib/'))
    (live / 'quiet.xml').write_text('<configuration><appender name="OUT" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %logger{30} %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="OUT"/></root></configuration>')
    command = ['docker', 'run', '--rm', '--user', '0', '--network', network,
               '--memory', '512m', '--cpus', '1', '--read-only', '--tmpfs', '/tmp:rw,size=64m',
               '--cap-drop=ALL', '--security-opt=no-new-privileges', '-v', str(live) + ':/live:ro',
               '--entrypoint', 'java', image, '-Dlogback.configurationFile=/live/quiet.xml', '-cp',
               '/live/classes:/live/lib/*:/live/BOOT-INF/classes:/live/BOOT-INF/lib/*',
               'com.bbb.exercise.agentdemo.orchestrator.execution.McpPublicDocumentationLiveTest']
    with (release / 'live-java.log').open('wb') as out:
        result = subprocess.run(command, stdout=out, stderr=subprocess.STDOUT, timeout=240)
    text = (release / 'live-java.log').read_text()
    assert result.returncode == 0 and 'LIVE_COMPLETE four tools' in text, 'Java SDK live verification failed; inspect private live-java.log'
    (release / 'live.status').write_text('COMPLETE\n')
    print('Verified all four real Java SDK tools and saved receipt replay without model billing', flush=True)


def approved_counts(container, timeout=45):
    address = next(n['IPAddress'] for n in container['NetworkSettings']['Networks'].values() if n.get('IPAddress'))
    env = dict(item.split('=', 1) for item in container['Config']['Env'])
    base = 'http://' + address + ':' + env.get('SERVER_PORT', '18084')
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(base + '/actuator/prometheus', timeout=10) as response:
                metrics = response.read(2000000).decode()
            counts = {server: float(value) for labels, value in re.findall(r'^agent_mcp_approved_tools\{([^}]*)\}\s+([0-9.]+)', metrics, re.M)
                      for server in re.findall(r'server="(mslearn|deepwiki)"', labels)}
            if counts == {'mslearn': 2.0, 'deepwiki': 2.0}:
                return counts
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError('Production MCP approval counts did not reach two tools per provider')
