"""Exercise source synchronization without downloading or building MapLibre."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class SourceSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        base = Path(self.temp.name)
        self.upstream = base / "upstream"
        self.root = base / "consumer"
        self.env = {
            **os.environ,
            "GIT_ALLOW_PROTOCOL": "file",
            "GIT_CONFIG_COUNT": "2",
            "GIT_CONFIG_KEY_0": "user.name",
            "GIT_CONFIG_VALUE_0": "Test",
            "GIT_CONFIG_KEY_1": "user.email",
            "GIT_CONFIG_VALUE_1": "test@localhost",
        }
        for repo in (self.upstream, self.root):
            repo.mkdir()
            self.run_at(repo, "git", "init")
        (self.upstream / "value").write_text("original\n")
        self.run_at(self.upstream, "git", "add", "value")
        self.run_at(self.upstream, "git", "commit", "-m", "Upstream")
        self.run_at(
            self.root,
            "git",
            "submodule",
            "add",
            str(self.upstream),
            "third_party/maplibre-gl-js",
        )
        self.source = self.root / "third_party/maplibre-gl-js"
        self.patches = self.root / "patches/maplibre-gl-js"
        self.patches.mkdir(parents=True)
        self.script = self.root / ".mise/bin/sync-maplibre-gl-js"
        self.script.parent.mkdir(parents=True)
        shutil.copyfile(
            Path(__file__).resolve().parents[1] / ".mise/bin/sync-maplibre-gl-js",
            self.script,
        )
        self.patch("0001.patch", "first\n")
        self.patch("0002.patch", "second\n")
        self.run_at(self.source, "git", "reset", "--hard", "HEAD")

    def run_at(self, directory, *args, check=True):
        return subprocess.run(
            args,
            cwd=directory,
            env=self.env,
            text=True,
            capture_output=True,
            check=check,
        )

    def patch(self, name, contents):
        (self.source / "value").write_text(contents)
        diff = self.run_at(self.source, "git", "diff", "--binary").stdout
        (self.patches / name).write_text(diff)
        self.run_at(self.source, "git", "add", "value")

    def sync(self, *args, check=True):
        return self.run_at(self.root, "bash", str(self.script), *args, check=check)

    def test_overlapping_patches_are_idempotent_and_can_be_removed(self):
        self.sync()
        self.assertEqual((self.source / "value").read_text(), "second\n")
        git_dir = Path(
            self.run_at(
                self.source, "git", "rev-parse", "--absolute-git-dir"
            ).stdout.strip()
        )
        stamp = git_dir / "maplibre-compose-sync.stamp"
        modified = stamp.stat().st_mtime_ns
        self.sync()
        self.assertEqual(stamp.stat().st_mtime_ns, modified)
        (self.patches / "0002.patch").unlink()
        self.sync()
        self.assertEqual((self.source / "value").read_text(), "first\n")
        (self.patches / "0001.patch").unlink()
        self.sync()
        self.assertEqual((self.source / "value").read_text(), "original\n")

    def test_local_edits_require_explicit_force(self):
        self.sync()
        (self.source / "value").write_text("local edit\n")
        self.assertNotEqual(self.sync(check=False).returncode, 0)
        self.assertEqual((self.source / "value").read_text(), "local edit\n")
        self.sync("force")
        self.assertEqual((self.source / "value").read_text(), "second\n")


if __name__ == "__main__":
    unittest.main()
