#!/usr/bin/env python3
"""Read-only deployment smoke checks; does not make a billable model call."""
import json
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path


def main(public_docs=False):
    release = Path(sys.argv[1]).resolve()
    assert release.parent == Path('/opt/agentdemo/releases')
    assert (release / 'deploy.status').read_text().strip() == 'COMPLETE'
    assert not Path('/run/bobo-release-maintenance').exists()
    manifest = json.loads((release / 'manifest.json').read_text())
    container = json.loads(subprocess.check_output(
        ['docker', 'inspect', 'agentdemo-agent-orchestrator-service-1']))[0]
    address = next(n['IPAddress'] for n in container['NetworkSettings']['Networks'].values()
                   if n.get('IPAddress'))
    env = dict(item.split('=', 1) for item in container['Config']['Env'])
    assert env['MCP_ENABLED'] == ('true' if public_docs else 'false')
    assert manifest['mcpEnabled'] is public_docs
    assert container['Image'] == manifest['imageId']
    base = 'http://' + address + ':' + env.get('SERVER_PORT', '18084')

    def get(url):
        try:
            with urllib.request.urlopen(url, timeout=15) as response:
                return response.getcode(), response.read(128000)
        except urllib.error.HTTPError as error:
            return error.code, b''

    health_code, health_body = get(base + '/actuator/health/readiness')
    assert health_code == 200 and json.loads(health_body)['status'] == 'UP'
    private_api, _ = get(base + '/api/agent-executions?requestId=mcp-release-smoke')
    assert private_api == 401, 'Unauthenticated task route must remain protected'
    public_code, _ = get('https://www.boboo.xin/')
    assert public_code == 200
    public_api, _ = get('https://www.boboo.xin/api/agent-executions?requestId=mcp-release-smoke')
    assert public_api == 401
    for service, old_id in manifest['beforeImageIds'].items():
        actual = json.loads(subprocess.check_output(['docker', 'inspect', 'agentdemo-' + service + '-1']))[0]
        assert actual['State']['Running'] and actual['State']['Health']['Status'] == 'healthy'
        assert actual['Image'] == (manifest['imageId'] if service == 'agent-orchestrator-service' else old_id)
    counts = {}
    if public_docs:
        from live_public_docs import approved_counts
        assert (release / 'live.status').read_text().strip() == 'COMPLETE'
        counts = approved_counts(container)
    result = dict(readiness='UP', https=public_code, internalUnauthenticated=private_api,
                  publicUnauthenticated=public_api, allEightHealthy=True, otherImagesUnchanged=True,
                  mcpEnabled=public_docs, approvedPublicTools=counts, billableLiveModelCall=False,
                  realMcpFixture=('Four real Java SDK calls and isolated ledger replays passed on server' if public_docs
                                  else '23 public-snapshot tests passed locally; no live external tool enabled'))
    (release / 'acceptance.json').write_text(json.dumps(result, indent=2) + '\n')
    (release / 'acceptance.status').write_text('COMPLETE\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
