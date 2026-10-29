"""Release-note extraction and existing version semantics with disposable tags."""

from __future__ import annotations

import pathlib
import shutil
import subprocess
import tempfile
import unittest

from tools.release.release_notes import tag_notes

ROOT = pathlib.Path(__file__).resolve().parents[1]
NOTES = "Maps retain their camera position.\n\n- Replace `old()` with `new()`.\n  Keep this indentation."


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.repository = pathlib.Path(directory.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "release@example.com")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "tag.gpgsign", "false")
        self.git(
            "commit", "--quiet", "--allow-empty", "-m", "Commit body must not leak"
        )

    def git(self, *args: str, input: str | None = None) -> str:
        return subprocess.run(
            ["git", "-C", str(self.repository), *args],
            input=input,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def notes_cli(self, tag: str) -> str:
        return subprocess.run(
            ["python3", str(ROOT / "tools/release/release_notes.py"), tag],
            cwd=self.repository,
            check=True,
            capture_output=True,
            text=True,
        ).stdout

    def test_annotated_tag_preserves_markdown_and_normalizes_final_newline(
        self,
    ) -> None:
        for suffix in ("", "\n", "\n\n"):
            with self.subTest(suffix=suffix):
                self.git(
                    "tag",
                    "-a",
                    "-f",
                    "--cleanup=verbatim",
                    "-F",
                    "-",
                    "v0.20.0",
                    input=f"MapLibre Compose 0.20.0\n\n{NOTES}{suffix}",
                )
                self.assertEqual(NOTES, tag_notes(self.repository, "v0.20.0"))
                self.assertEqual(NOTES + "\n", self.notes_cli("v0.20.0"))

    def test_lightweight_and_title_only_tags_use_fallback(self) -> None:
        self.git("tag", "v0.20.0")
        self.git("tag", "-a", "-m", "MapLibre Compose 0.20.1", "v0.20.1")
        for tag in ("v0.20.0", "v0.20.1"):
            with self.subTest(tag=tag):
                self.assertEqual("", tag_notes(self.repository, tag))
                self.assertEqual(f"MapLibre Compose {tag[1:]}.\n", self.notes_cli(tag))

    @unittest.skipUnless(shutil.which("ssh-keygen"), "SSH signing needs ssh-keygen")
    def test_signed_tag_excludes_signature(self) -> None:
        key = self.repository / "signing-key"
        subprocess.run(
            ["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(key)],
            check=True,
            capture_output=True,
        )
        self.git("config", "gpg.format", "ssh")
        self.git("config", "user.signingkey", str(key))
        self.git(
            "tag",
            "-s",
            "--cleanup=verbatim",
            "-F",
            "-",
            "v0.20.0",
            input=f"MapLibre Compose 0.20.0\n\n{NOTES}\n",
        )
        self.assertIn("BEGIN SSH SIGNATURE", self.git("cat-file", "tag", "v0.20.0"))
        self.assertEqual(NOTES + "\n", self.notes_cli("v0.20.0"))

    def test_version_modes_keep_existing_release_and_snapshot_semantics(self) -> None:
        self.git("tag", "-a", "-m", "MapLibre Compose 0.19.0", "v0.19.0")
        old_commit = self.git("rev-parse", "HEAD")
        self.git("commit", "--quiet", "--allow-empty", "-m", "Next release")
        self.git("tag", "-a", "-m", "MapLibre Compose 0.20.0", "v0.20.0")

        def version(mode: str) -> subprocess.CompletedProcess[str]:
            return subprocess.run(
                ["bash", str(ROOT / ".mise/bin/version-args"), mode],
                cwd=self.repository,
                check=False,
                capture_output=True,
                text=True,
            )

        self.git("checkout", "--quiet", old_commit)
        for mode in ("build", "release"):
            result = version(mode)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("-PmaplibreVersion=0.19.0\n", result.stdout)
            self.assertIn("-PmaplibreSourceRef=v0.19.0\n", result.stdout)
        self.assertIn("-PmaplibreVersion=0.20.1-SNAPSHOT\n", version("snapshot").stdout)
        self.git("commit", "--quiet", "--allow-empty", "-m", "Untagged")
        self.assertIn("-PmaplibreVersion=0.20.1-SNAPSHOT\n", version("build").stdout)
        self.assertNotEqual(0, version("release").returncode)


if __name__ == "__main__":
    unittest.main()
