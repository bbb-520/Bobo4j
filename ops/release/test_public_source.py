import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

MODULE = Path(__file__).with_name('public_source.py')


class PublicSourceTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.is_file(), 'Missing repeatable public source exporter')
        spec = importlib.util.spec_from_file_location('public_source', MODULE)
        self.exporter = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.exporter)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-b', 'codex/development')
        self.git('config', 'user.name', 'Release Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.write('pom.xml', '<project><modules><module>platform-parent</module><module>agent-api</module><module>agent-architecture-tests</module></modules></project>')
        for module in ('platform-parent', 'agent-api', 'agent-architecture-tests'):
            self.write(module+'/pom.xml', '<project/>')
        self.write('README.md', '# Public backend\n')
        self.write('.gitignore', 'target/\n.env\nsecrets/\n')
        self.write('agent-api/src/main/java/Api.java', 'class Api {}')
        self.write('agent-api/src/main/resources/application.yml', 'server:\n  port: 8080\n')
        self.write('agent-api/src/test/java/ApiTest.java', 'class ApiTest {}')
        self.write('ops/release/private-runbook.md', 'deployment maintenance')
        self.write('docs/design.md', 'private design')
        self.write('.env', 'PRIVATE_VALUE=secret')
        self.write('secrets/key.pem', 'private credential')
        self.git('add', '--all')
        self.git('commit', '-m', 'Complete development source')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True, encoding='utf-8', stderr=subprocess.DEVNULL).strip()

    def write(self, name, content):
        p = self.root/name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding='utf-8')

    def prepare(self):
        return self.exporter.prepare(self.root, 'HEAD', None)

    def test_export_preserves_core_and_removes_private_files_and_test_module(self):
        release = self.prepare()
        files = set(self.git('ls-tree', '-r', '--name-only', release['publicCommit']).splitlines())
        self.assertEqual(files, {'README.md', '.gitignore', 'pom.xml', 'platform-parent/pom.xml', 'agent-api/pom.xml', 'agent-api/src/main/java/Api.java', 'agent-api/src/main/resources/application.yml'})
        self.assertNotIn('agent-architecture-tests', self.git('show', release['publicCommit']+':pom.xml'))
        self.assertIn('agent-api', self.git('show', release['publicCommit']+':pom.xml'))
        self.assertEqual(self.git('branch', '--show-current'), 'codex/development')
        self.assertEqual(self.git('status', '--porcelain'), '')

    def test_public_history_cannot_include_private_development_ancestors(self):
        source = self.git('rev-parse', 'HEAD')
        release = self.prepare()
        ancestry = self.git('rev-list', release['publicCommit']).splitlines()
        self.assertNotIn(source, ancestry)
        self.assertEqual(len(ancestry), 1)

    def test_export_tree_is_reproducible(self):
        first = self.prepare()
        second = self.prepare()
        self.assertEqual(first['publicTree'], second['publicTree'])

    def test_public_readme_uses_committed_public_template(self):
        self.write('README.md', '# Full development\nSee private operational documents.\n')
        self.write('ops/release/public-README.md', '# Core backend\nPublic build instructions.\n')
        self.git('add', '--all'); self.git('commit', '-m', 'Keep both documentation views')
        release = self.prepare()
        self.assertEqual(self.git('show', release['publicCommit']+':README.md'), '# Core backend\nPublic build instructions.')

    def test_readme_art_only_allows_three_named_assets(self):
        self.write('assets/readme/hero.svg', '<svg xmlns="http://www.w3.org/2000/svg"/>')
        self.write('assets/readme/private-notes.md', 'Private notes')
        self.git('add', '--all'); self.git('commit', '-m', 'Add README illustration')
        release = self.prepare()
        files = self.git('ls-tree', '-r', '--name-only', release['publicCommit']).splitlines()
        self.assertIn('assets/readme/hero.svg', files)
        self.assertNotIn('assets/readme/private-notes.md', files)
        self.exporter.validate_push(self.root, 'git@github.com:bbb-520/Bobo4j.git', [('refs/heads/main', release['publicCommit'])])

    def test_selected_release_retains_unreleased_features_only_in_development(self):
        previous = self.prepare()['publicCommit']
        self.write('agent-api/src/main/java/Api.java', 'class Api { int futureFeature; }')
        self.write('README.md', '# Selected release\n')
        self.git('add', '--all'); self.git('commit', '-m', 'Work containing a future feature')
        release = self.exporter.prepare(self.root, 'HEAD', previous, ['README.md'])
        self.assertEqual(self.git('show', release['publicCommit']+':agent-api/src/main/java/Api.java'), 'class Api {}')
        self.assertEqual(self.git('show', release['publicCommit']+':README.md'), '# Selected release')
        self.assertEqual(release['selectedPaths'], ['README.md'])
        self.assertEqual(self.git('show', 'HEAD:agent-api/src/main/java/Api.java'), 'class Api { int futureFeature; }')

    def test_selected_move_deletes_only_explicitly_selected_old_core(self):
        previous = self.prepare()['publicCommit']
        old = 'agent-api/src/main/java/Api.java'
        self.git('rm', old, 'agent-api/src/main/resources/application.yml')
        self.write('pom.xml', '<project><modules><module>platform-parent</module><module>agent-api</module><module>agent-mcp</module></modules></project>')
        self.write('agent-mcp/pom.xml', '<project/>')
        self.write('agent-mcp/src/main/java/Mcp.java', 'class Mcp {}')
        self.write('agent-mcp/src/test/java/McpTest.java', 'class McpTest {}')
        self.git('add', '--all'); self.git('commit', '-m', 'Extract MCP and unrelated pending removal')
        release = self.exporter.prepare(self.root, 'HEAD', previous,
                                        ['pom.xml', 'agent-mcp/pom.xml', 'agent-mcp/src/main/java/Mcp.java'],
                                        removed_paths=[old])
        files = self.git('ls-tree', '-r', '--name-only', release['publicCommit']).splitlines()
        self.assertNotIn(old, files)
        self.assertIn('agent-mcp/src/main/java/Mcp.java', files)
        self.assertIn('agent-api/src/main/resources/application.yml', files)
        self.assertNotIn('agent-mcp/src/test/java/McpTest.java', files)
        self.assertEqual(release['removedPaths'], [old])
        self.assertNotIn(self.git('rev-parse', 'HEAD'), self.git('rev-list', release['publicCommit']).splitlines())

    def test_selected_removal_rejects_private_present_missing_overlap_and_unscoped_paths(self):
        previous = self.prepare()['publicCommit']
        for path in ['ops/release/private-runbook.md', 'agent-api/src/main/java/Api.java',
                     'agent-api/src/main/java/Missing.java', 'README.md']:
            with self.assertRaisesRegex(ValueError, 'remov|Remov'):
                self.exporter.prepare(self.root, 'HEAD', previous, ['README.md'], removed_paths=[path])
        self.git('rm', 'agent-api/src/main/java/Api.java'); self.git('commit', '-m', 'Remove old class')
        with self.assertRaisesRegex(ValueError, 'remov|Remov'):
            self.exporter.prepare(self.root, 'HEAD', previous, None,
                                  removed_paths=['agent-api/src/main/java/Api.java'])

    def test_modified_source_and_untracked_business_source_are_rejected(self):
        self.write('agent-api/src/main/java/Api.java', 'class Api { int changed; }')
        with self.assertRaisesRegex(ValueError, 'uncommitted'):
            self.prepare()
        self.git('checkout', '--', 'agent-api/src/main/java/Api.java')
        self.write('agent-api/src/main/java/Untracked.java', 'class Untracked {}')
        with self.assertRaisesRegex(ValueError, 'uncommitted'):
            self.prepare()

    def test_selected_release_rejects_private_or_missing_paths(self):
        previous = self.prepare()['publicCommit']
        for paths in (['ops/release/private-runbook.md'], ['agent-api/src/main/java/Missing.java'], []):
            with self.assertRaisesRegex(ValueError, 'selected|Selected'):
                self.exporter.prepare(self.root, 'HEAD', previous, paths)

    def test_credentials_in_otherwise_allowed_paths_are_rejected(self):
        self.write('agent-api/src/main/resources/private.pem', '-----BEGIN PRIVATE KEY-----\nprivate\n')
        self.git('add', '--all'); self.git('commit', '-m', 'Accidental credential')
        with self.assertRaisesRegex(ValueError, 'credential'):
            self.prepare()

    def test_push_guard_rejects_private_branches_and_private_ancestry(self):
        source = self.git('rev-parse', 'HEAD')
        public = self.prepare()['publicCommit']
        self.exporter.validate_push(self.root, 'https://github.com/bbb-520/Bobo4j.git', [('refs/heads/main', public)])
        for ref, revision in [('refs/heads/codex/development', source), ('refs/heads/main', source), ('refs/tags/private-baseline', source)]:
            with self.assertRaisesRegex(ValueError, 'private|public'):
                self.exporter.validate_push(self.root, 'git@github.com:bbb-520/Bobo4j.git', [(ref, revision)])


if __name__ == '__main__':
    unittest.main()
