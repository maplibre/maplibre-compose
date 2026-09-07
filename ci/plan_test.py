"""Exercise tier selection and the actual required-check shell command."""

from __future__ import annotations

import json
import os
import pathlib
import subprocess
import tempfile
import textwrap
import unittest

from ci.plan import JOBS, TIERS, plan, variants

ROOT = pathlib.Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"
CALLERS = {"draft": "ci.yml", "ready": "ci-ready.yml", "full": "ci-full.yml"}


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


def names(selection: dict) -> set[str]:
    return {row["name"] for job in JOBS[1:] for row in selection[job]["include"]}


def catalog_names(*tiers: str) -> set[str]:
    return {row["name"] for row in variants() if row["tier"] in tiers}


class CatalogTest(unittest.TestCase):
    def test_every_variant_has_a_unique_name_and_a_single_tier(self) -> None:
        rows = variants()
        self.assertEqual(len({row["name"] for row in rows}), len(rows))
        for row in rows:
            self.assertIn(row["tier"], TIERS)
            self.assertIn(row["job"], JOBS)

    def test_draft_keeps_compile_and_linux_runtime_coverage(self) -> None:
        self.assertEqual(
            catalog_names("draft"),
            {
                "hygiene",
                "docs",
                "js",
                "ios device",
                "android 36",
                "desktop linux-x64",
            },
        )

    def test_ready_adds_the_remaining_runtimes_once(self) -> None:
        self.assertEqual(
            catalog_names("ready"),
            {
                "ios simulator",
                "android 26",
                "desktop macos-arm64",
                "desktop windows-x64",
            },
        )
        self.assertEqual(
            catalog_names("full"), {"desktop linux-arm64", "desktop windows-arm64"}
        )


class PlanTest(unittest.TestCase):
    def assert_selected(self, selection: dict, expected: set[str]) -> None:
        self.assertEqual(names(selection), expected)
        for job in JOBS[1:]:
            rows = selection[job]["include"]
            self.assertEqual(job in selection["run"], bool(rows))
            self.assertEqual(
                selection["expected"][job], "success" if rows else "skipped"
            )
            for row in rows:
                self.assertNotIn("tier", row)
        self.assertEqual(selection["expected"]["plan"], "success")

    def test_each_tier_runs_only_its_own_variants_on_a_ready_pr(self) -> None:
        for tier in TIERS:
            with self.subTest(tier=tier):
                selection = plan(tier, "pull_request", pr_event())
                self.assertEqual(selection["tier"], tier)
                self.assert_selected(
                    selection, catalog_names(tier) if tier != "full" else set()
                )

    def test_draft_pr_runs_only_the_draft_tier(self) -> None:
        for tier in TIERS:
            with self.subTest(tier=tier):
                selection = plan(tier, "pull_request", pr_event(draft=True))
                self.assert_selected(
                    selection, catalog_names(tier) if tier == "draft" else set()
                )

    def test_opt_in_label_requires_every_tier_even_on_drafts(self) -> None:
        for draft in [True, False]:
            for action in ["labeled", "synchronize", "ready_for_review"]:
                for tier in TIERS:
                    with self.subTest(draft=draft, action=action, tier=tier):
                        selection = plan(
                            tier,
                            "pull_request",
                            pr_event(draft, ("ci:full",), action=action),
                        )
                        self.assert_selected(selection, catalog_names(tier))

    def test_unrelated_label_uses_current_pr_state(self) -> None:
        event = pr_event(False, ("infra",), action="labeled", label={"name": "infra"})
        self.assert_selected(
            plan("ready", "pull_request", event), catalog_names("ready")
        )
        self.assert_selected(plan("full", "pull_request", event), set())

    def test_dependabot_prs_require_every_tier_after_maintainer_events(self) -> None:
        for draft in [True, False]:
            for action, sender in [
                ("opened", "dependabot[bot]"),
                ("synchronize", "maintainer"),
                ("labeled", "maintainer"),
            ]:
                for tier in TIERS:
                    with self.subTest(draft=draft, action=action, tier=tier):
                        selection = plan(
                            tier,
                            "pull_request",
                            pr_event(
                                draft,
                                author="dependabot[bot]",
                                action=action,
                                sender={"login": sender},
                            ),
                        )
                        self.assert_selected(selection, catalog_names(tier))

    def test_dependabot_sender_does_not_expand_another_authors_pr(self) -> None:
        event = pr_event(draft=True, sender={"login": "dependabot[bot]"})
        self.assert_selected(plan("ready", "pull_request", event), set())

    def test_push_and_dispatch_run_every_variant_in_one_workflow(self) -> None:
        for event in ["push", "workflow_dispatch"]:
            selection = plan("draft", event, {})
            self.assert_selected(selection, catalog_names(*TIERS))

    def test_missing_pr_metadata_fails_closed(self) -> None:
        for event in [
            {},
            {"pull_request": {}},
            {"pull_request": {"labels": []}},
            {"pull_request": {"labels": [], "draft": False}},
        ]:
            for tier in TIERS:
                with self.assertRaises(KeyError):
                    plan(tier, "pull_request", event)

    def test_unknown_tier_fails_closed(self) -> None:
        with self.assertRaises(ValueError):
            plan("all", "push", {})


class PlanCommandTest(unittest.TestCase):
    def test_task_runs_outside_checkout_without_mise_environment(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            event = root / "event.json"
            output = root / "output"
            summary = root / "summary"
            event.write_text(json.dumps(pr_event(draft=False)))
            subprocess.run(
                ["bash", str(ROOT / ".mise/tasks/ci/plan")],
                cwd=root,
                env={
                    "PATH": os.environ["PATH"],
                    "CI_TIER": "ready",
                    "GITHUB_EVENT_NAME": "pull_request",
                    "GITHUB_EVENT_PATH": str(event),
                    "GITHUB_OUTPUT": str(output),
                    "GITHUB_STEP_SUMMARY": str(summary),
                },
                check=True,
                capture_output=True,
                text=True,
            )
            values = dict(
                line.split("=", 1) for line in output.read_text().splitlines()
            )
            self.assertEqual(values["tier"], "ready")
            self.assertEqual(json.loads(values["run"]), ["android", "ios", "desktop"])
            desktop = json.loads(values["desktop"])["include"]
            self.assertEqual(
                [row["runner"] for row in desktop], ["macos-26", "windows-2022"]
            )
            self.assertEqual(json.loads(values["expected"])["js"], "skipped")
            self.assertIn("CI tier: **ready**", summary.read_text())
            self.assertIn("ios simulator", summary.read_text())


class WorkflowTest(unittest.TestCase):
    @staticmethod
    def pr_events(workflow: str) -> set[str]:
        text = (WORKFLOWS / workflow).read_text()
        pr = text.split("  pull_request:\n", 1)[1].split("\n\n", 1)[0]
        return {
            line.strip().removeprefix("- ")
            for line in pr.splitlines()
            if line.strip().startswith("- ")
        }

    def test_only_the_tier_an_event_can_add_reruns_on_it(self) -> None:
        code = {"opened", "synchronize", "reopened"}
        self.assertEqual(self.pr_events("ci.yml"), code)
        self.assertEqual(
            self.pr_events("ci-ready.yml"), code | {"ready_for_review", "labeled"}
        )
        self.assertEqual(self.pr_events("ci-full.yml"), code | {"labeled"})

    def test_main_and_manual_runs_use_a_single_workflow(self) -> None:
        for tier, workflow in CALLERS.items():
            text = (WORKFLOWS / workflow).read_text()
            self.assertIn(f"tier: {tier}\n", text)
            self.assertEqual("  push:\n" in text, tier == "draft")
            self.assertEqual("  workflow_dispatch:\n" in text, tier == "draft")

    def test_every_job_is_planned_and_reaches_the_required_check(self) -> None:
        text = (WORKFLOWS / "ci-jobs.yml").read_text()
        for job in JOBS[1:]:
            self.assertIn(f"contains(fromJSON(needs.plan.outputs.run), '{job}')", text)
            self.assertIn(
                f"matrix: ${{{{ fromJSON(needs.plan.outputs.{job}) }}}}", text
            )
        needs = text.split("  all-good:\n", 1)[1].split("    timeout-minutes:", 1)[0]
        needed = {
            line.strip().removeprefix("- ")
            for line in needs.splitlines()
            if line.strip().startswith("- ")
        }
        self.assertEqual(needed, set(JOBS))


class RequiredCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        workflow = (WORKFLOWS / "ci-jobs.yml").read_text()
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
            for tier in TIERS:
                expected = plan(tier, "pull_request", event)["expected"]
                results = {job: {"result": result} for job, result in expected.items()}
                self.assertTrue(self.check(expected, results))
                for job, wanted in expected.items():
                    for outcome in ["success", "skipped", "failure", "cancelled"]:
                        if outcome == wanted:
                            continue
                        with self.subTest(tier=tier, job=job, outcome=outcome):
                            self.assertFalse(
                                self.check(
                                    expected, {**results, job: {"result": outcome}}
                                )
                            )

    def test_planner_cannot_allow_failure_or_cancellation(self) -> None:
        for outcome in ["failure", "cancelled"]:
            expected = {**plan("draft", "push", {})["expected"], "ios": outcome}
            results = {job: {"result": value} for job, value in expected.items()}
            with self.subTest(outcome=outcome):
                self.assertFalse(self.check(expected, results))

    def test_missing_plan_or_missing_or_unexpected_job_fails(self) -> None:
        expected = plan("draft", "push", {})["expected"]
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
