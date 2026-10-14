import tempfile
import unittest
from pathlib import Path

from maintenance import MaintenanceGuard


class MaintenanceTests(unittest.TestCase):
    def guard(self, directory):
        return MaintenanceGuard(Path(directory) / 'maintenance', Path(directory) / 'status')

    def test_drain_failure_without_mutation_can_reopen(self):
        with tempfile.TemporaryDirectory() as directory:
            guard = self.guard(directory)
            with self.assertRaises(RuntimeError), guard:
                raise RuntimeError('drain failed')
            self.assertFalse(guard.flag.exists())
            self.assertEqual(guard.status.read_text(), 'DEFERRED\n')

    def test_partial_stop_failure_remains_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            guard = self.guard(directory)
            with self.assertRaises(RuntimeError), guard:
                guard.mark_changed()
                raise RuntimeError('stop partially failed')
            self.assertTrue(guard.flag.exists())
            self.assertEqual(guard.status.read_text(), 'FAILED_MAINTENANCE\n')

    def test_failed_rollback_never_reopens(self):
        with tempfile.TemporaryDirectory() as directory:
            guard = self.guard(directory)
            guard.status.write_text('ROLLED_BACK\n')
            with self.assertRaises(RuntimeError), guard:
                self.assertEqual(guard.status.read_text(), 'IN_PROGRESS\n')
                guard.mark_changed()
                try:
                    raise RuntimeError('migration failed')
                except RuntimeError:
                    raise RuntimeError('old image failed readiness')
            self.assertTrue(guard.flag.exists())
            self.assertEqual(guard.status.read_text(), 'FAILED_MAINTENANCE\n')

    def test_verified_release_reopens(self):
        with tempfile.TemporaryDirectory() as directory:
            guard = self.guard(directory)
            with guard:
                guard.mark_changed()
                guard.accept('COMPLETE')
            self.assertFalse(guard.flag.exists())
            self.assertEqual(guard.status.read_text(), 'COMPLETE\n')

    def test_verified_rollback_reopens_even_when_release_reports_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            guard = self.guard(directory)
            with self.assertRaises(RuntimeError), guard:
                guard.mark_changed()
                guard.accept('ROLLED_BACK')
                raise RuntimeError('release deferred; rollback verified')
            self.assertFalse(guard.flag.exists())
            self.assertEqual(guard.status.read_text(), 'ROLLED_BACK\n')

    def test_invalid_acceptance_cannot_open_traffic(self):
        with tempfile.TemporaryDirectory() as directory:
            guard = self.guard(directory)
            with self.assertRaises(ValueError), guard:
                guard.mark_changed()
                guard.accept('STARTED')
            self.assertTrue(guard.flag.exists())


if __name__ == '__main__':
    unittest.main()
