"""Exercise CI tier changes and the actual required-check shell command."""

from __future__ import annotations

import json
import os
import pathlib
import subprocess
import textwrap
import unittest

from ci.pr_matrix import plan

ROOT = pathlib.Path(__file__).resolve().parents[1]
ALL_RUNNERS = {
    "ubuntu-24.04",
    "ubuntu-24.04-arm",
    "macos-26",
    "windows-2022",
    "windows-11-arm",
}


def pr_event(
    draft: bool = False,
    labels: tuple[str, ...] = (),
    *,
    author: str = "contributor",
    **extra,
) -> dict:
    return {
        "pull_request": {
            "draft": draft,
            "labels": [{"name": name} for name in labels],
            "user": {"login": author},
        },
        **extra,
    }


class PlanTest(unittest.TestCase):
    def assert_runners(self, selection: dict, expected: set[str]) -> None:
        rows = selection["desktop"]["include"]
        self.assertEqual({row["runner"] for row in rows}, expected)
        self.assertEqual(len(rows), len(expected))
        for row in rows:
            self.assertTrue(row["artifact_name"].startswith("demo-app-desktop-"))
            self.assertIn(row["artifact_type"], {"dmg", "msi", "appimage"})

    def test_draft_keeps_compile_and_linux_runtime_coverage(self) -> None:
        selection = plan("pull_request", pr_event(draft=True))
        self.assertEqual(selection["tier"], "draft")
        self.assert_runners(selection, {"ubuntu-24.04"})
        self.assertEqual(selection["expected"]["ios"], "skipped")
        for job in ["android", "ios-device", "js", "desktop", "docs", "hygiene"]:
            self.assertEqual(selection["expected"][job], "success")

    def test_ready_keeps_each_desktop_graphics_bridge(self) -> None:
        selection = plan("pull_request", pr_event(action="ready_for_review"))
        self.assertEqual(selection["tier"], "ready")
        self.assert_runners(selection, {"ubuntu-24.04", "macos-26", "windows-2022"})
        self.assertEqual(set(selection["expected"].values()), {"success"})

    def test_opt_in_persists_across_events_and_overrides_draft(self) -> None:
        for draft in [True, False]:
            for action in [
                "labeled",
                "synchronize",
                "ready_for_review",
                "converted_to_draft",
            ]:
                with self.subTest(draft=draft, action=action):
                    selection = plan(
                        "pull_request", pr_event(draft, ("ci:full",), action=action)
                    )
                    self.assertEqual(selection["tier"], "full")
                    self.assert_runners(selection, ALL_RUNNERS)
                    self.assertEqual(set(selection["expected"].values()), {"success"})

    def test_removed_or_unrelated_label_uses_current_pr_state(self) -> None:
        for draft, tier in [(True, "draft"), (False, "ready")]:
            selection = plan(
                "pull_request",
                pr_event(
                    draft, ("infra",), action="unlabeled", label={"name": "ci:full"}
                ),
            )
            self.assertEqual(selection["tier"], tier)

    def test_dependabot_prs_always_run_full_even_after_maintainer_events(self) -> None:
        for draft in [True, False]:
            for action, sender in [
                ("opened", "dependabot[bot]"),
                ("synchronize", "maintainer"),
                ("unlabeled", "maintainer"),
            ]:
                with self.subTest(draft=draft, action=action, sender=sender):
                    selection = plan(
                        "pull_request",
                        pr_event(
                            draft,
                            author="dependabot[bot]",
                            action=action,
                            sender={"login": sender},
                        ),
                    )
                    self.assertEqual(selection["tier"], "full")
                    self.assert_runners(selection, ALL_RUNNERS)
                    self.assertEqual(set(selection["expected"].values()), {"success"})

    def test_dependabot_sender_does_not_expand_another_authors_pr(self) -> None:
        selection = plan(
            "pull_request", pr_event(draft=True, sender={"login": "dependabot[bot]"})
        )
        self.assertEqual(selection["tier"], "draft")

    def test_push_and_dispatch_always_run_full(self) -> None:
        for event in ["push", "workflow_dispatch"]:
            selection = plan(event, pr_event(draft=True))
            self.assertEqual(selection["tier"], "full")
            self.assert_runners(selection, ALL_RUNNERS)

    def test_missing_pr_metadata_fails_closed(self) -> None:
        for event in [
            {},
            {"pull_request": {}},
            {"pull_request": {"labels": []}},
            {"pull_request": {"labels": [], "draft": False}},
        ]:
            with self.assertRaises(KeyError):
                plan("pull_request", event)


class WorkflowEventsTest(unittest.TestCase):
    def test_readiness_and_label_changes_recompute_the_tier(self) -> None:
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        pr = workflow.split("  pull_request:\n", 1)[1].split("  workflow_dispatch:", 1)[
            0
        ]
        events = {
            line.strip().removeprefix("- ")
            for line in pr.splitlines()
            if line.strip().startswith("- ")
        }
        self.assertEqual(
            events,
            {
                "opened",
                "synchronize",
                "reopened",
                "ready_for_review",
                "converted_to_draft",
                "labeled",
                "unlabeled",
            },
        )


class RequiredCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        step = workflow.split(
            "      - name: Check that every selected job succeeded\n", 1
        )[1]
        cls.script = textwrap.dedent(step.split("        run: |\n", 1)[1])

    def check(self, expected: dict, results: dict) -> bool:
        result = subprocess.run(
            ["bash", "-e", "-c", self.script],
            env={
                **os.environ,
                "EXPECTED": json.dumps(expected),
                "RESULTS": json.dumps(results),
            },
            check=False,
            capture_output=True,
            text=True,
        )
        return result.returncode == 0

    def test_every_tier_accepts_only_its_expected_results(self) -> None:
        for event in [pr_event(True), pr_event(), pr_event(True, ("ci:full",))]:
            expected = plan("pull_request", event)["expected"]
            results = {job: {"result": result} for job, result in expected.items()}
            self.assertTrue(self.check(expected, results))
            for job, wanted in expected.items():
                for outcome in ["success", "skipped", "failure", "cancelled"]:
                    if outcome == wanted:
                        continue
                    with self.subTest(
                        job=job, outcome=outcome, draft=event["pull_request"]["draft"]
                    ):
                        self.assertFalse(
                            self.check(expected, {**results, job: {"result": outcome}})
                        )

    def test_planner_cannot_allow_failure_or_cancellation(self) -> None:
        for outcome in ["failure", "cancelled"]:
            expected = {**plan("push", {})["expected"], "ios": outcome}
            results = {job: {"result": value} for job, value in expected.items()}
            with self.subTest(outcome=outcome):
                self.assertFalse(self.check(expected, results))

    def test_missing_plan_or_missing_or_unexpected_job_fails(self) -> None:
        expected = plan("push", {})["expected"]
        results = {job: {"result": "success"} for job in expected}
        self.assertFalse(self.check({}, results))
        self.assertFalse(self.check({}, {}))
        self.assertFalse(
            self.check(
                expected, {job: value for job, value in results.items() if job != "ios"}
            )
        )
        self.assertFalse(
            self.check(expected, {**results, "unplanned": {"result": "success"}})
        )


if __name__ == "__main__":
    unittest.main()
