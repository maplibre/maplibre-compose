"""Tests for the GitHub Actions pin check."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from ci.action_pins import check_pins

SHA_A = "a" * 40
SHA_B = "b" * 40


class CheckPinsTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = pathlib.Path(directory.name)

    def _write(self, relative: str, *uses: str) -> None:
        path = self.root / ".github" / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("steps:\n" + "".join(f"  - uses: {u}\n" for u in uses))

    def test_matching_pins_in_workflows_and_composite_actions_pass(self) -> None:
        self._write("workflows/ci.yml", f"actions/cache@{SHA_A} # v6", "./local")
        self._write("actions/setup/action.yml", f"actions/cache/save@{SHA_A} # v6")
        self.assertEqual([], check_pins(self.root))

    def test_unpinned_references_fail(self) -> None:
        self._write(
            "actions/setup/action.yml",
            "actions/cache@v6",
            f"actions/checkout@{SHA_A}",
        )
        problems = check_pins(self.root)
        self.assertEqual(2, len(problems))
        self.assertIn(
            ".github/actions/setup/action.yml:2: actions/cache@v6", problems[0]
        )

    def test_a_composite_action_left_behind_fails(self) -> None:
        self._write("workflows/ci.yml", f"actions/cache@{SHA_B} # v7")
        self._write("actions/setup/action.yml", f"actions/cache/restore@{SHA_A} # v6")
        problems = check_pins(self.root)
        self.assertEqual(1, len(problems))
        self.assertIn("actions/cache is pinned to", problems[0])

    def test_a_mismatched_version_comment_fails(self) -> None:
        self._write(
            "workflows/ci.yml",
            f"actions/cache@{SHA_A} # v6",
            f"actions/cache@{SHA_A} # v7",
        )
        self.assertEqual(1, len(check_pins(self.root)))


if __name__ == "__main__":
    unittest.main()
