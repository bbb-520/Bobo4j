import importlib.util,json,pathlib,tempfile,unittest,hashlib
spec=importlib.util.spec_from_file_location('release_manifest',str(pathlib.Path(__file__).with_name('verify_manifest.py')))
release=importlib.util.module_from_spec(spec)
class ManifestTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):spec.loader.exec_module(release)
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.temp.name)
  self.doc={'source':{'backendCommit':'a'*40,'frontendCommit':'b'*40},'services':{},'frontend':{}}
  for name in release.SERVICES:
   folder=self.root/name;folder.mkdir();(folder/'app.jar').write_bytes(name.encode())
   self.doc['services'][name]={'sha256':hashlib.sha256(name.encode()).hexdigest(),'runtimeId':'sha256:'+'c'*64}
  (self.root/'web.tar.gz').write_bytes(b'web');self.doc['frontend']={'sha256':hashlib.sha256(b'web').hexdigest(),'runtimeId':'sha256:'+'d'*64}
 def tearDown(self):self.temp.cleanup()
 def test_valid_complete_tested_payload(self):release.verify(self.root,self.doc,'a'*40,'b'*40)
 def test_jar_tampering_is_rejected(self):
  (self.root/'agent-auth-service/app.jar').write_bytes(b'changed')
  with self.assertRaises(ValueError):release.verify(self.root,self.doc,'a'*40,'b'*40)
 def test_missing_service_or_untrusted_revision_rejected(self):
  with self.assertRaises(ValueError):release.verify(self.root,self.doc,'e'*40,'b'*40)
  self.doc['services'].pop('agent-media-service')
  with self.assertRaises(ValueError):release.verify(self.root,self.doc,'a'*40,'b'*40)
 def test_runtime_tag_without_immutable_identity_rejected(self):
  self.doc['frontend']['runtimeId']='node:latest'
  with self.assertRaises(ValueError):release.verify(self.root,self.doc,'a'*40,'b'*40)
if __name__=='__main__':unittest.main()
