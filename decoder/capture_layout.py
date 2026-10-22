"""One run, four responsibility directories: workload, dut, ref and diff."""
from pathlib import Path


class CaptureLayout:
    def __init__(self, root):
        self.root = Path(root)
        self.workload = self.root / 'workload'
        self.dut = self.root / 'dut'
        self.ref = self.root / 'ref'
        self.diff = self.root / 'diff'

    def create(self):
        for path in (self.workload, self.dut, self.ref, self.diff):
            path.mkdir(parents=True, exist_ok=True)
        return self

    def input(self, category, name):
        """Read legacy flat captures too; all new outputs use category paths."""
        path = getattr(self, category) / name
        return path if path.exists() else self.root / name
