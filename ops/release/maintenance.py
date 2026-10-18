"""Fail closed after any deployment mutation until release/rollback is verified."""
from pathlib import Path


class MaintenanceGuard:
    def __init__(self, flag: Path, status: Path):
        self.flag = flag
        self.status = status
        self.changed = False
        self.verified = False

    def __enter__(self):
        self.flag.touch(mode=0o644)
        self.status.write_text('IN_PROGRESS\n')
        return self

    def mark_changed(self):
        # Call BEFORE the first potentially partial mutation, including stop.
        self.changed = True

    def accept(self, status):
        if status not in ('COMPLETE', 'ROLLED_BACK'):
            raise ValueError('Only a verified release or rollback may reopen traffic')
        self.status.write_text(status + '\n')
        self.verified = True

    def __exit__(self, error_type, error, traceback):
        if self.changed and not self.verified:
            self.status.write_text('FAILED_MAINTENANCE\n')
        else:
            if not self.verified:
                self.status.write_text('DEFERRED\n')
            try:
                self.flag.unlink()
            except FileNotFoundError:
                pass
        return False
