import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

GIT_BASH = Path("C:/Program Files/Git/bin/bash.exe")
BASH = str(GIT_BASH) if os.name == "nt" and GIT_BASH.exists() else shutil.which("bash")
SCRIPT = Path(__file__).resolve().parents[1] / "deploy/auto-update.sh"


@unittest.skipUnless(shutil.which("git") and BASH, "git and bash required")
class DeployTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.origin = self.root / "origin.git"
        self.source = self.root / "source"
        self.checkout = self.root / "checkout"
        self.git("init", "--bare", str(self.origin))
        self.git("init", "-b", "main", str(self.source))
        self.git("config", "core.autocrlf", "false", cwd=self.source)
        self.git("config", "user.name", "Deploy test", cwd=self.source)
        self.git("config", "user.email", "test@example.invalid", cwd=self.source)
        (self.source / "deploy").mkdir()
        (self.source / "deploy/update.sh").write_text("#!/usr/bin/env bash\nset -e\n[[ ! -f fail ]]\nprintf done >> deploy-count\n", encoding="utf-8")
        self.git("add", ".", cwd=self.source)
        self.git("commit", "-m", "Initial", cwd=self.source)
        self.git("remote", "add", "origin", str(self.origin), cwd=self.source)
        self.git("push", "origin", "main", cwd=self.source)
        self.git("-c", "core.autocrlf=false", "clone", "-b", "main", str(self.origin), str(self.checkout))
        self.env = dict(os.environ, BRUGMONITOR_DIR=self.checkout.as_posix(), XDG_STATE_HOME=(self.root / "state").as_posix(), XDG_RUNTIME_DIR=self.root.as_posix())

    def git(self, *args, cwd=None):
        return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()

    def deploy(self):
        # Passing stdin also tests the GitHub workflow's git-show | bash invocation.
        script = SCRIPT.read_text(encoding="utf-8")
        # Git Bash has no flock; Linux uses the real lock, Windows mocks that dependency.
        if os.name == "nt":
            script = "flock() { return 0; }\n" + script
        return subprocess.run([BASH, "-s"], input=script, env=self.env, capture_output=True, text=True)

    def test_deploys_new_main_and_does_not_restart_twice(self):
        (self.source / "new.txt").write_text("new release", encoding="utf-8")
        self.git("add", ".", cwd=self.source)
        self.git("commit", "-m", "Update", cwd=self.source)
        self.git("push", "origin", "main", cwd=self.source)
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.checkout / "new.txt").exists())
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.checkout), self.git("rev-parse", "HEAD", cwd=self.source))
        self.assertEqual(self.deploy().returncode, 0)
        self.assertEqual((self.checkout / "deploy-count").read_text(), "done")

    def test_tracked_local_changes_block_deployment(self):
        (self.checkout / "deploy/update.sh").write_text("local changes", encoding="utf-8")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.checkout / "deploy-count").exists())
        self.assertEqual((self.checkout / "deploy/update.sh").read_text(), "local changes")

    def test_failed_deploy_is_retried_on_same_commit(self):
        (self.checkout / "fail").touch()
        self.assertNotEqual(self.deploy().returncode, 0)
        self.assertFalse((self.root / "state/brugmonitor/deployed-commit").exists())
        (self.checkout / "fail").unlink()
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "state/brugmonitor/deployed-commit").exists())
