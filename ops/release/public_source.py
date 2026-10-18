#!/usr/bin/env python3
"""Export committed development source without publishing private files or ancestry."""
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath

PUBLIC_ROOT = {'README.md', 'pom.xml', '.gitignore', '.gitattributes', 'platform-parent/pom.xml'}
README_ART = {'assets/readme/hero.svg', 'assets/readme/task-flow.svg', 'assets/readme/architecture.svg'}
CREDENTIAL = re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----|\bsk-[A-Za-z0-9_-]{24,}')
PUBLIC_IGNORE = '\n# Private development and generated output\n**/src/test/\n/agent-architecture-tests/\n/ops/\n/infra/\n/scripts/\n/docs/\n/config/\n/.github/\n/.mvn/\n/mvnw*\n/Dockerfile*\n/*compose*.yml\n/.dockerignore\n**/README.md\n!/README.md\n**/evaluation/\n'


def git(root, *args, data=None, env=None):
    process = subprocess.run(['git', *args], cwd=root, input=data, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, env=env)
    if process.returncode:
        raise ValueError('Git operation failed: '+args[0])
    return process.stdout


def revision(root, ref):
    value = git(root, 'rev-parse', '--verify', ref+'^{commit}').decode().strip()
    if not re.fullmatch('[0-9a-f]{40}', value):
        raise ValueError('Invalid source revision')
    return value


def entries(root, ref):
    result = {}
    for record in git(root, 'ls-tree', '-rz', ref).split(b'\0'):
        if not record:
            continue
        metadata, name = record.split(b'\t', 1)
        mode, kind, oid = metadata.decode().split()
        result[name.decode('utf-8')] = (mode, kind, oid)
    return result


def allowed(name):
    parts = PurePosixPath(name).parts
    if name in PUBLIC_ROOT or name in README_ART:
        return True
    return bool(parts and re.fullmatch(r'agent-[a-z0-9-]+', parts[0])
                and parts[0] != 'agent-architecture-tests'
                and (len(parts) == 2 and parts[1] == 'pom.xml'
                     or len(parts) >= 4 and parts[1:3] == ('src', 'main')))


def check_credential(name, content):
    parts = PurePosixPath(name).parts
    if (any(p in {'secrets', '.env', 'id_rsa', 'id_ed25519'} for p in parts)
            or Path(name).suffix.lower() in {'.pem', '.key', '.p12', '.pfx'}
            or CREDENTIAL.search(content)):
        raise ValueError('Potential credential in public source: '+name)


def public_blobs(root, source):
    files = entries(root, source)
    output = {}
    for name, (mode, kind, oid) in files.items():
        if not allowed(name):
            continue
        if kind != 'blob' or mode not in {'100644', '100755'}:
            raise ValueError('Linked/non-file public source is forbidden: '+name)
        content = git(root, 'cat-file', 'blob', oid)
        check_credential(name, content)
        if name == 'pom.xml':
            content = re.sub(rb'\s*<module>agent-architecture-tests</module>', b'', content)
        elif name == '.gitignore':
            content += PUBLIC_IGNORE.encode()
        output[name] = (mode, content)
    for required in ('README.md', 'pom.xml', 'platform-parent/pom.xml'):
        if required not in output:
            raise ValueError('Missing public build file: '+required)
    template = files.get('ops/release/public-README.md')
    if template:
        if template[0] not in {'100644', '100755'} or template[1] != 'blob':
            raise ValueError('Invalid public README template')
        content = git(root, 'cat-file', 'blob', template[2])
        check_credential('README.md', content)
        output['README.md'] = ('100644', content)
    pom = ET.fromstring(output['pom.xml'][1])
    for module in pom.findall('.//{*}modules/{*}module'):
        if module.text is None or module.text+'/pom.xml' not in output:
            raise ValueError('Missing public Maven module')
    return output


def prepare(root, source='HEAD', public_parent=None, selected_paths=None, removed_paths=None):
    root = Path(root).resolve()
    # Ignored environment/secrets/build files stay private; every source file must be committed.
    if git(root, 'status', '--porcelain', '--untracked-files=all').strip():
        raise ValueError('Development source contains uncommitted files')
    source = revision(root, source)
    if source != revision(root, 'HEAD'):
        raise ValueError('Export the checked-out verified source revision')
    parent = revision(root, public_parent) if public_parent else None
    if parent and entries(root, parent).get('ops/release/public_source.py'):
        raise ValueError('Public parent contains private development source')
    blobs = public_blobs(root, source)
    removed_paths = sorted(set(removed_paths or []))
    if removed_paths and selected_paths is None:
        raise ValueError('Removals require an explicit selected release')
    if selected_paths is not None:
        if not parent or not selected_paths:
            raise ValueError('Selected release requires a public parent and paths')
        selected_paths = sorted(set(selected_paths))
        for name in selected_paths:
            if not allowed(name) or name not in blobs:
                raise ValueError('Invalid selected public path: '+name)
        previous = {}
        for name, (mode, kind, oid) in entries(root, parent).items():
            if not allowed(name) or mode not in {'100644', '100755'} or kind != 'blob':
                raise ValueError('Public parent contains private or linked files')
            content = git(root, 'cat-file', 'blob', oid)
            check_credential(name, content)
            previous[name] = (mode, content)
        for name in removed_paths:
            if not allowed(name) or name not in previous or name in blobs or name in selected_paths:
                raise ValueError('Invalid selected removal: '+name)
        for name in removed_paths:
            del previous[name]
        previous.update({name: blobs[name] for name in selected_paths})
        blobs = previous
        pom = ET.fromstring(blobs['pom.xml'][1])
        for module in pom.findall('.//{*}modules/{*}module'):
            if module.text is None or module.text+'/pom.xml' not in blobs:
                raise ValueError('Missing selected public Maven module')
    with tempfile.TemporaryDirectory(prefix='bobo-public-index-') as temp:
        environment = dict(os.environ, GIT_INDEX_FILE=str(Path(temp)/'index'))
        git(root, 'read-tree', '--empty', env=environment)
        records = []
        for name, (mode, content) in sorted(blobs.items()):
            oid = git(root, 'hash-object', '-w', '--stdin', data=content).decode().strip()
            records.append((mode+' '+oid+'\t'+name).encode()+b'\0')
        git(root, 'update-index', '-z', '--index-info', data=b''.join(records), env=environment)
        tree = git(root, 'write-tree', env=environment).decode().strip()
    args = ['commit-tree', tree]
    if parent:
        args += ['-p', parent]
    commit = git(root, *args, data=('Publish verified core source\n\nDevelopment-Commit: '+source+'\n').encode()).decode().strip()
    return {'developmentCommit': source, 'publicParent': parent, 'publicCommit': commit,
            'publicTree': tree, 'publicFiles': len(blobs), 'selectedPaths': selected_paths,
            'removedPaths': removed_paths}


def validate_push(root, remote_url, updates):
    if 'github.com' not in remote_url.lower():
        raise ValueError('Only the configured public GitHub publication is supported')
    for update in updates:
        ref, commit = update[:2]
        old = update[2] if len(update) > 2 else None
        if ref != 'refs/heads/main' or commit == '0'*40:
            raise ValueError('Only public main may be pushed; private branches/tags stay local')
        commit = revision(root, commit)
        if old and old != '0'*40:
            old = revision(root, old)
            git(root, 'merge-base', '--is-ancestor', old, commit)
            revisions = git(root, 'rev-list', old+'..'+commit).decode().splitlines()
        else:
            revisions = git(root, 'rev-list', commit).decode().splitlines()
        # Previously public history is preserved; every newly introduced commit must be public-only.
        for value in set(revisions+[commit]):
            for name, (mode, kind, oid) in entries(root, value).items():
                if not allowed(name):
                    raise ValueError('Push would expose private development files/history')
                if mode not in {'100644', '100755'} or kind != 'blob':
                    raise ValueError('Invalid public file')
                check_credential(name, git(root, 'cat-file', 'blob', oid))


def install_guard(root):
    hook = Path(git(root, 'rev-parse', '--git-path', 'hooks/pre-push').decode().strip())
    if not hook.is_absolute():
        hook = Path(root)/hook
    marker = '# BOBO public-source guard'
    if hook.exists() and marker not in hook.read_text(encoding='utf-8'):
        raise ValueError('Existing pre-push hook requires manual integration')
    script = Path(__file__).resolve()
    hook.parent.mkdir(parents=True, exist_ok=True)
    hook.write_text('#!/bin/sh\n'+marker+'\nexec '
                    +shlex.quote(sys.executable.replace('\\', '/'))+' '
                    +shlex.quote(str(script).replace('\\', '/'))+' --guard "$@"\n',
                    encoding='utf-8', newline='\n')
    hook.chmod(0o755)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--push', metavar='RECORD')
    parser.add_argument('--record')
    parser.add_argument('--path', action='append', dest='selected_paths',
                        help='Overlay only this public file; repeat for a selected release')
    parser.add_argument('--remove', action='append', dest='removed_paths',
                        help='Remove an absent development file from the public parent; requires --path')
    parser.add_argument('--install-guard', action='store_true')
    parser.add_argument('--guard', nargs=2, metavar=('REMOTE', 'URL'))
    args = parser.parse_args(); root = Path(args.root).resolve()
    if args.guard:
        updates = [(p[2], p[1], p[3]) for line in sys.stdin for p in [line.split()] if len(p) == 4]
        validate_push(root, args.guard[1], updates); return
    if args.install_guard:
        install_guard(root); print('Public-only pre-push guard installed'); return
    if args.prepare:
        git(root, 'fetch', 'origin', 'main')
        record = prepare(root, 'HEAD', 'refs/remotes/origin/main', args.selected_paths, args.removed_paths)
        if args.record:
            Path(args.record).write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
        print(json.dumps(record, indent=2)); return
    if args.push:
        record = json.loads(Path(args.push).read_text(encoding='utf-8'))
        if git(root, 'status', '--porcelain', '--untracked-files=all').strip() or revision(root, 'HEAD') != record['developmentCommit']:
            raise ValueError('Development source changed after verification')
        remote_url = git(root, 'remote', 'get-url', 'origin').decode().strip()
        if not re.search(r'github\.com[:/]bbb-520/Bobo4j(?:\.git)?$', remote_url):
            raise ValueError('Unexpected publication repository')
        head = git(root, 'ls-remote', 'origin', 'refs/heads/main').decode().split()[0]
        if head != record['publicParent']:
            raise ValueError('Public main changed; prepare and verify a new export')
        validate_push(root, remote_url, [('refs/heads/main', record['publicCommit'], head)])
        git(root, 'push', 'origin', record['publicCommit']+':refs/heads/main')
        git(root, 'update-ref', 'refs/heads/main', record['publicCommit'])
        git(root, 'fetch', 'origin', 'main')
        print('Published core source '+record['publicCommit']); return
    parser.error('Choose --prepare, --push, or --install-guard')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr); sys.exit(1)
