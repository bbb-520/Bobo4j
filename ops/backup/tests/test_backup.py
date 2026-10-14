"""Offline safety tests; no Docker, database, network, or paid model calls."""
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest import mock

SOURCE = Path(__file__).resolve().parents[1] / "backup.py"
spec = importlib.util.spec_from_file_location("backup", str(SOURCE))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class SafetyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.key = os.urandom(32)

    def tearDown(self):
        self.tmp.cleanup()

    def test_private_prefix_is_mandatory_and_cannot_be_bucket_root(self):
        for prefix in ("", "/", "photos/", "private/", "private/../photos/", "private/x//"):
            with self.assertRaises(ValueError):
                backup.checked_prefix(prefix)
        self.assertEqual("private/agent-backup/", backup.checked_prefix("private/agent-backup/"))

    def test_retention_never_selects_foreign_or_current_objects(self):
        prefix = "private/agent-backup/"
        old = prefix + "20200101T000000Z-" + "a" * 32 + ".abk"
        latest = prefix + "20260101T000000Z-" + "b" * 32 + ".abk"
        keys = [old, latest, "photos/" + old, prefix + "nested/" + old,
                prefix + "20200101T000000Z-not-owned.abk", prefix + "keep.txt"]
        self.assertEqual([old], backup.retention_candidates(keys, prefix, latest, 1577923200, 5))
        with self.assertRaises(ValueError):
            backup.retention_candidates(keys, prefix, latest, 1577923200, 0)

    def test_no_retention_after_failed_upload_validation(self):
        store = mock.Mock()
        store.validate.side_effect = RuntimeError("failed")
        with self.assertRaises(RuntimeError):
            backup.upload_and_retain(store, "private/agent-backup/", "current", self.root / "x", self.key, 30, 5)
        store.list_keys.assert_not_called()
        store.delete.assert_not_called()

    def test_archive_path_traversal_symlink_hardlink_and_duplicate_are_rejected(self):
        for name, kind in (("../escape", tarfile.REGTYPE), ("/escape", tarfile.REGTYPE),
                           ("safe/link", tarfile.SYMTYPE), ("safe/hard", tarfile.LNKTYPE)):
            path = self.root / "bad.tar.gz"
            with tarfile.open(str(path), "w:gz") as archive:
                item = tarfile.TarInfo(name)
                item.type = kind
                item.linkname = "../../escape"
                archive.addfile(item)
            with self.assertRaises(ValueError):
                backup.safe_extract(path, self.root / "out")
        path = self.root / "dupe.tar.gz"
        with tarfile.open(str(path), "w:gz") as archive:
            for unused in range(2):
                archive.addfile(tarfile.TarInfo("same"))
        with self.assertRaises(ValueError):
            backup.safe_extract(path, self.root / "out")

    def test_metadata_and_ciphertext_are_authenticated_before_decrypt(self):
        header = backup.envelope_header()
        encrypted = self.root / "cipher"
        encrypted.write_bytes(b"fixture ciphertext" * 10)
        envelope = self.root / "archive.abk"
        backup.pack_envelope(encrypted, envelope, self.key, header)
        backup.authenticate(envelope, self.key)
        original = envelope.read_bytes()
        for offset in (16, len(original) // 2, len(original) - 1):
            changed = bytearray(original)
            changed[offset] ^= 1
            envelope.write_bytes(changed)
            with mock.patch.object(backup, "openssl_transform") as transform:
                with self.assertRaises(ValueError):
                    backup.decrypt(envelope, self.root / "clear", self.key, "openssl")
                transform.assert_not_called()
        envelope.write_bytes(original)
        with self.assertRaises(ValueError):
            backup.authenticate(envelope, os.urandom(32))

    def test_round_trip_with_real_openssl_and_wrong_key_no_plaintext(self):
        openssl = os.environ.get("BACKUP_TEST_OPENSSL") or shutil.which("openssl")
        if not openssl:
            self.skipTest("OpenSSL unavailable; set BACKUP_TEST_OPENSSL")
        plain, encrypted, restored = [self.root / n for n in ("plain", "encrypted", "restored")]
        plain.write_bytes(b"SQL and vector fixture\x00" * 1000)
        backup.encrypt(plain, encrypted, self.key, openssl)
        self.assertNotIn(b"SQL and vector", encrypted.read_bytes())
        backup.decrypt(encrypted, restored, self.key, openssl)
        self.assertEqual(plain.read_bytes(), restored.read_bytes())
        restored.unlink()
        with self.assertRaises(ValueError):
            backup.decrypt(encrypted, restored, os.urandom(32), openssl)
        self.assertFalse(restored.exists())

    def test_manifest_detects_missing_changed_or_extra_files(self):
        directory = self.root / "snapshot"
        directory.mkdir()
        (directory / "data").write_bytes(b"fixture")
        backup.write_manifest(directory, {"fixture": True})
        backup.verify_manifest(directory)
        (directory / "data").write_bytes(b"altered")
        with self.assertRaises(ValueError):
            backup.verify_manifest(directory)
        (directory / "data").write_bytes(b"fixture")
        (directory / "extra").write_bytes(b"unexpected")
        with self.assertRaises(ValueError):
            backup.verify_manifest(directory)

    def test_dump_has_consistency_and_program_objects_without_password_in_argv(self):
        cfg = {"mysql": {"defaults_file": "/root/client.cnf", "databases": ["agent", "nacos"]}}
        args = backup.mysql_dump_args(cfg)
        for required in ("--single-transaction", "--routines", "--events", "--triggers", "--hex-blob"):
            self.assertIn(required, args)
        self.assertEqual("--defaults-extra-file=/root/client.cnf", args[1])
        self.assertFalse(any("password" in arg for arg in args))

    def test_status_exporter_contract_preserves_last_success_on_failure(self):
        cfg = {"status_dir": str(self.root / "status")}
        backup.report(cfg, "backup", False, 0, {"last_success": 1234}, {"error_class": "ValueError"})
        status = json.loads((self.root / "status" / "status.json").read_text())
        self.assertEqual(1234, status["lastSuccessTimestamp"])
        self.assertEqual("FAILED", status["status"])
        backup.report(cfg, "restore-check", True, 0, status)
        status = json.loads((self.root / "status" / "status.json").read_text())
        self.assertEqual(1234, status["lastSuccessTimestamp"])
        self.assertEqual("SUCCESS", status["status"])
        self.assertIn("agent_backup_last_success_timestamp_seconds 1234", (self.root / "status" / "backup.prom").read_text())


if __name__ == "__main__":
    unittest.main()
