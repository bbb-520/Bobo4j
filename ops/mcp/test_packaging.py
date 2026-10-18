import io
import tempfile
import unittest
import zipfile
from pathlib import Path
from live_public_docs import verify_module_jar


class McpPackagingTest(unittest.TestCase):
    def artifact(self, root, nested=True, fat=False, stale=False):
        library=io.BytesIO()
        with zipfile.ZipFile(library,'w') as archive:
            prefix='BOOT-INF/classes/' if fat else ''
            archive.writestr(prefix+'com/bbb/exercise/agentdemo/mcp/McpConnectionManager.class',b'compiled')
            archive.writestr(prefix+'mcp/public-docs/wiki-question.input.json','{}')
        path=Path(root)/'app.jar'
        with zipfile.ZipFile(path,'w') as archive:
            if nested:archive.writestr('BOOT-INF/lib/agent-mcp-0.0.1-SNAPSHOT.jar',library.getvalue())
            if stale:archive.writestr('BOOT-INF/classes/com/bbb/exercise/agentdemo/orchestrator/mcp/McpConnectionManager.class',b'old')
        return path

    def test_plain_mcp_library_is_verified_inside_the_executable(self):
        with tempfile.TemporaryDirectory() as root:
            verify_module_jar(self.artifact(root))

    def test_missing_nested_library_fat_library_or_stale_business_copy_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            for options in ({'nested':False},{'fat':True},{'stale':True}):
                with self.assertRaises(AssertionError):verify_module_jar(self.artifact(root,**options))


if __name__=='__main__':unittest.main()
