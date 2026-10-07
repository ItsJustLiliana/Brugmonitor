"""Run the backend regression checks."""
from pathlib import Path
import sys
import unittest
base = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(base))
suite = unittest.defaultTestLoader.discover(str(base / "tests"))
result = unittest.TextTestRunner(verbosity=2).run(suite)
sys.exit(0 if result.wasSuccessful() else 1)
