"""Exercise tier selection, workflow composition, and the verdict script."""

from __future__ import annotations

import json
import os
import pathlib
import re
import subprocess
import tempfile
import unittest

from ci.plan import JOBS, TIERS, plan, resolve_restatement, tier_jobs, variants

ROOT = pathlib.Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"
REPO = "maplibre/maplibre-compose"
CALLERS = {"draft": "ci.yml", "ready": "ci-ready.yml", "full": "ci-full.yml"}


def pr_event(
    draft: bool = False,
    labels: tuple[str, ...] = (),
    *,
    author: str = "contributor",
    fork: bool = False,
    **extra,
) -> dict:
    return {
        "pull_request": {
            "draft": draft,
            "labels": [{"name": name} for name in labels],
            "user": {"login": author},
            "head": {"repo": {"full_name": "someone/fork" if fork else REPO}},
        },
        **extra,
    }


def names(selection: dict) -> set[str]:
    return {
        f"{job} / {variant}" for job in JOBS for variant in selection[job]["variant"]
    }


def catalog_names(*tiers: str) -> set[str]:
    return {
        f"{row['job']} / {row['variant']}" for row in variants() if row["tier"] in tiers
    }


class CatalogTest(unittest.TestCase):
    def test_every_variant_has_a_unique_name_and_a_single_tier(self) -> None:
        rows = variants()
        self.assertEqual(len({(row["job"], row["variant"]) for row in rows}), len(rows))
        for row in rows:
            self.assertIn(row["tier"], TIERS)
            self.assertIn(row["job"], JOBS)

    def test_draft_keeps_compile_and_linux_runtime_coverage(self) -> None:
        self.assertEqual(
            catalog_names("draft"),
            {
                "hygiene / ubuntu",
                "docs / ubuntu",
                "js / chromium-firefox",
                "ios-device / arm64",
                "android / 36",
                "desktop / linux-x64",
            },
        )

    def test_ready_adds_the_remaining_runtimes_once(self) -> None:
        self.assertEqual(
            catalog_names("ready"),
            {
                "ios / arm64",
                "android / 26",
                "desktop / macos-arm64",
                "desktop / windows-x64",
            },
        )
        self.assertEqual(
            catalog_names("full"), {"desktop / linux-arm64", "desktop / windows-arm64"}
        )


class PlanTest(unittest.TestCase):
    def plan(self, tier: str, event_name: str, event: dict) -> dict:
        return plan(tier, event_name, event, REPO)

    def assert_selected(self, selection: dict, expected: set[str]) -> None:
        self.assertEqual(names(selection), expected)
        self.assertEqual(selection["selected"], bool(expected))
        self.assertEqual(selection["check"], f"all-good ({selection['tier']})")
        for job in JOBS:
            rows = selection[job]["include"]
            self.assertEqual(
                selection[job]["variant"], [row["variant"] for row in rows]
            )
            for row in rows:
                self.assertNotIn("tier", row)
                self.assertNotIn("job", row)

    def assert_restated(self, selection: dict) -> None:
        self.assert_selected(selection, set())
        self.assertTrue(selection["restate"])

    def test_each_tier_runs_only_its_own_variants_on_a_ready_pr(self) -> None:
        for tier in TIERS:
            with self.subTest(tier=tier):
                selection = self.plan(tier, "pull_request", pr_event())
                self.assertEqual(selection["tier"], tier)
                self.assert_selected(
                    selection, catalog_names(tier) if tier != "full" else set()
                )
                self.assertFalse(selection["restate"])

    def test_draft_pr_runs_only_the_draft_tier(self) -> None:
        for tier in TIERS:
            with self.subTest(tier=tier):
                selection = self.plan(tier, "pull_request", pr_event(draft=True))
                self.assert_selected(
                    selection, catalog_names(tier) if tier == "draft" else set()
                )

    def test_code_events_run_every_required_tier(self) -> None:
        for draft in [True, False]:
            for action in ["opened", "synchronize", "reopened"]:
                for tier in TIERS:
                    with self.subTest(draft=draft, action=action, tier=tier):
                        selection = self.plan(
                            tier,
                            "pull_request",
                            pr_event(draft, ("ci:full",), action=action),
                        )
                        self.assert_selected(selection, catalog_names(tier))
                        self.assertFalse(selection["restate"])

    def test_ready_for_review_runs_only_the_ready_tier(self) -> None:
        event = pr_event(action="ready_for_review")
        self.assert_selected(
            self.plan("ready", "pull_request", event), catalog_names("ready")
        )
        selection = self.plan("full", "pull_request", event)
        self.assert_selected(selection, set())
        self.assertFalse(selection["restate"])
        # The draft tier was required before, so its verdict stands.
        self.assert_restated(self.plan("draft", "pull_request", event))

    def test_ready_for_review_after_opt_in_restates_the_ready_tier(self) -> None:
        event = pr_event(False, ("ci:full",), action="ready_for_review")
        self.assert_restated(self.plan("ready", "pull_request", event))
        self.assert_restated(self.plan("full", "pull_request", event))

    def test_opt_in_label_runs_only_the_tiers_it_adds(self) -> None:
        label = {"name": "ci:full"}
        on_draft = pr_event(True, ("ci:full",), action="labeled", label=label)
        self.assert_restated(self.plan("draft", "pull_request", on_draft))
        self.assert_selected(
            self.plan("ready", "pull_request", on_draft), catalog_names("ready")
        )
        self.assert_selected(
            self.plan("full", "pull_request", on_draft), catalog_names("full")
        )
        on_ready = pr_event(False, ("ci:full",), action="labeled", label=label)
        self.assert_restated(self.plan("ready", "pull_request", on_ready))
        self.assert_selected(
            self.plan("full", "pull_request", on_ready), catalog_names("full")
        )

    def test_unrelated_label_restates_the_required_tiers(self) -> None:
        label = {"name": "infra"}
        event = pr_event(False, ("infra",), action="labeled", label=label)
        self.assert_restated(self.plan("ready", "pull_request", event))
        selection = self.plan("full", "pull_request", event)
        self.assert_selected(selection, set())
        self.assertFalse(selection["restate"])
        opted_in = pr_event(False, ("ci:full", "infra"), action="labeled", label=label)
        self.assert_restated(self.plan("full", "pull_request", opted_in))

    def test_removing_the_opt_in_label_records_the_downgraded_tier(self) -> None:
        label = {"name": "ci:full"}
        on_ready = pr_event(False, ("infra",), action="unlabeled", label=label)
        self.assert_restated(self.plan("ready", "pull_request", on_ready))
        selection = self.plan("full", "pull_request", on_ready)
        self.assert_selected(selection, set())
        self.assertFalse(selection["restate"])
        on_draft = pr_event(True, (), action="unlabeled", label=label)
        for tier in ["ready", "full"]:
            selection = self.plan(tier, "pull_request", on_draft)
            self.assert_selected(selection, set())
            self.assertFalse(selection["restate"])

    def test_label_event_without_label_metadata_fails_closed(self) -> None:
        for action in ["labeled", "unlabeled"]:
            with self.assertRaises(KeyError):
                self.plan("ready", "pull_request", pr_event(action=action))

    def test_dependabot_prs_require_every_tier_after_maintainer_events(self) -> None:
        for draft in [True, False]:
            for action, sender in [
                ("opened", "dependabot[bot]"),
                ("synchronize", "maintainer"),
            ]:
                for tier in TIERS:
                    with self.subTest(draft=draft, action=action, tier=tier):
                        selection = self.plan(
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
            for tier in TIERS:
                with self.subTest(draft=draft, action="labeled", tier=tier):
                    event = pr_event(
                        draft,
                        ("infra",),
                        author="dependabot[bot]",
                        action="labeled",
                        label={"name": "infra"},
                    )
                    self.assert_restated(self.plan(tier, "pull_request", event))

    def test_dependabot_sender_does_not_expand_another_authors_pr(self) -> None:
        event = pr_event(draft=True, sender={"login": "dependabot[bot]"})
        self.assert_selected(self.plan("ready", "pull_request", event), set())

    def test_push_and_dispatch_run_each_tier_in_its_own_workflow(self) -> None:
        for event in ["push", "workflow_dispatch"]:
            for tier in TIERS:
                selection = self.plan(tier, event, {})
                self.assert_selected(selection, catalog_names(tier))
                self.assertFalse(selection["restate"])

    def test_secrets_only_for_trusted_heads(self) -> None:
        self.assertTrue(self.plan("draft", "push", {})["secrets"])
        self.assertTrue(self.plan("draft", "pull_request", pr_event())["secrets"])
        self.assertFalse(
            self.plan("draft", "pull_request", pr_event(fork=True))["secrets"]
        )
        dependabot = pr_event(author="dependabot[bot]")
        self.assertFalse(self.plan("draft", "pull_request", dependabot)["secrets"])
        deleted_fork = pr_event()
        deleted_fork["pull_request"]["head"]["repo"] = None
        self.assertFalse(self.plan("draft", "pull_request", deleted_fork)["secrets"])
        self.assertTrue(self.plan("draft", "workflow_dispatch", {})["secrets"])
        for secrets in ["false", False]:
            event = {"inputs": {"secrets": secrets}}
            self.assertFalse(self.plan("draft", "workflow_dispatch", event)["secrets"])

    def test_missing_pr_metadata_fails_closed(self) -> None:
        for event in [
            {},
            {"pull_request": {}},
            {"pull_request": {"labels": []}},
            {"pull_request": {"labels": [], "draft": False}},
            {"pull_request": {"labels": [], "draft": False, "user": {"login": "a"}}},
        ]:
            for tier in TIERS:
                with self.assertRaises(KeyError):
                    self.plan(tier, "pull_request", event)

    def test_unknown_tier_fails_closed(self) -> None:
        with self.assertRaises(ValueError):
            self.plan("all", "push", {})


class CoverageReuseTest(unittest.TestCase):
    @staticmethod
    def checks(tier: str) -> list[dict]:
        return [
            {
                "id": index,
                "name": f"{tier} / {name}",
                "status": "completed",
                "conclusion": "success",
            }
            for index, name in enumerate(sorted(catalog_names(tier)))
        ]

    def selection(self, tier: str) -> dict:
        return plan(
            tier,
            "pull_request",
            pr_event(
                author="dependabot[bot]",
                action="labeled",
                labels=("dependencies",),
                label={"name": "dependencies"},
            ),
            REPO,
        )

    def test_dependabot_label_burst_replaces_pending_producer(self) -> None:
        # Regardless of which event survives the pending queue, it must produce
        # the required coverage when opened/synchronize never reached its jobs.
        for tier in ("ready", "full"):
            for label in ("dependencies", "javascript"):
                event = pr_event(
                    author="dependabot[bot]",
                    action="labeled",
                    labels=(label,),
                    label={"name": label},
                )
                selection = resolve_restatement(
                    plan(tier, "pull_request", event, REPO), []
                )
                self.assertEqual(names(selection), catalog_names(tier))
                self.assertTrue(selection["selected"])
                self.assertFalse(selection["restate"])
                self.assertFalse(selection["secrets"])

    def test_complete_platform_success_can_be_reused(self) -> None:
        for tier in ("ready", "full"):
            selection = resolve_restatement(self.selection(tier), self.checks(tier))
            self.assertFalse(selection["selected"])
            self.assertTrue(selection["restate"])

    def test_missing_running_failed_or_cancelled_platform_runs_again(self) -> None:
        for status, conclusion in (
            ("queued", None),
            ("in_progress", None),
            ("completed", "failure"),
            ("completed", "cancelled"),
            ("completed", "skipped"),
        ):
            checks = self.checks("ready")
            checks[0] = {**checks[0], "status": status, "conclusion": conclusion}
            selection = resolve_restatement(self.selection("ready"), checks)
            self.assertEqual(names(selection), catalog_names("ready"))
        selection = resolve_restatement(
            self.selection("ready"), self.checks("ready")[1:]
        )
        self.assertEqual(names(selection), catalog_names("ready"))

    def test_latest_platform_check_wins_across_workflow_runs(self) -> None:
        checks = self.checks("ready")
        failed = {**checks[0], "id": 100, "conclusion": "failure"}
        for responses in (checks + [failed], [failed] + checks):
            self.assertTrue(
                resolve_restatement(self.selection("ready"), responses)["selected"]
            )
        recovered = {**failed, "id": 101, "conclusion": "success"}
        self.assertFalse(
            resolve_restatement(self.selection("ready"), checks + [failed, recovered])[
                "selected"
            ]
        )

    def test_aggregate_success_or_another_tier_is_not_platform_coverage(self) -> None:
        checks = self.checks("full") + [
            {
                "id": 100,
                "name": "all-good (ready)",
                "status": "completed",
                "conclusion": "success",
            }
        ]
        self.assertEqual(
            names(resolve_restatement(self.selection("ready"), checks)),
            catalog_names("ready"),
        )

    def test_code_events_still_run_and_unrequired_tiers_stay_skipped(self) -> None:
        for action in ("opened", "synchronize", "reopened"):
            selection = plan("ready", "pull_request", pr_event(action=action), REPO)
            self.assertEqual(
                names(resolve_restatement(selection, self.checks("ready"))),
                catalog_names("ready"),
            )
        selection = plan("full", "pull_request", pr_event(), REPO)
        self.assertEqual(names(resolve_restatement(selection, [])), set())


class PlanCommandTest(unittest.TestCase):
    def test_label_plan_reads_exact_head_and_fails_closed_on_api_error(self) -> None:
        for checks, api_exit in (
            (CoverageReuseTest.checks("ready"), 0),
            ([], 0),
            ([], 1),
        ):
            with (
                self.subTest(checks=bool(checks), api_exit=api_exit),
                tempfile.TemporaryDirectory() as directory,
            ):
                root = pathlib.Path(directory)
                event = pr_event(
                    author="dependabot[bot]",
                    action="labeled",
                    labels=("javascript",),
                    label={"name": "javascript"},
                )
                event["pull_request"]["head"]["sha"] = "exact-head"
                (root / "event.json").write_text(json.dumps(event))
                # Multiple pages must contribute coverage, including an empty page.
                (root / "checks.json").write_text(
                    json.dumps(
                        [
                            {"check_runs": checks[:1]},
                            {"check_runs": []},
                            {"check_runs": checks[1:]},
                        ]
                    )
                )
                gh = root / "gh"
                gh.write_text(
                    '#!/bin/sh\ncd "$FAKE_ROOT"\nprintf "%s\\n" "$@" > args\ncat checks.json\n'
                    + f"exit {api_exit}\n"
                )
                gh.chmod(0o755)
                result = subprocess.run(
                    ["bash", str(ROOT / ".mise/tasks/ci/plan")],
                    cwd=root,
                    env={
                        "PATH": f"{root}:{os.environ['PATH']}",
                        "FAKE_ROOT": str(root),
                        "CI_TIER": "ready",
                        "GITHUB_EVENT_NAME": "pull_request",
                        "GITHUB_EVENT_PATH": str(root / "event.json"),
                        "GITHUB_REPOSITORY": REPO,
                        "GITHUB_OUTPUT": str(root / "output"),
                        "GITHUB_STEP_SUMMARY": str(root / "summary"),
                    },
                    capture_output=True,
                    text=True,
                    check=False,
                )
                args = (root / "args").read_text().splitlines()
                self.assertIn(f"repos/{REPO}/commits/exact-head/check-runs", args)
                self.assertIn("filter=latest", args)
                self.assertIn("--paginate", args)
                if api_exit:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse((root / "output").exists())
                else:
                    self.assertEqual(result.returncode, 0, result.stderr)
                    output = (root / "output").read_text()
                    self.assertIn(f"selected={str(not checks).lower()}\n", output)
                    self.assertIn(f"restate={str(bool(checks)).lower()}\n", output)
                    self.assertIn("secrets=false\n", output)

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
                    "GITHUB_REPOSITORY": REPO,
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
            self.assertEqual(values["check"], "all-good (ready)")
            self.assertEqual(values["selected"], "true")
            self.assertEqual(values["restate"], "false")
            self.assertEqual(values["secrets"], "true")
            desktop = json.loads(values["desktop"])
            self.assertEqual(desktop["variant"], ["macos-arm64", "windows-x64"])
            self.assertEqual(
                [row["runner"] for row in desktop["include"]],
                ["macos-26", "windows-2022"],
            )
            self.assertEqual(json.loads(values["js"])["include"], [])
            self.assertIn("CI tier: **ready**", summary.read_text())
            self.assertIn("ios / arm64", summary.read_text())


class WorkflowTest(unittest.TestCase):
    @staticmethod
    def pr_events(workflow: str) -> set[str]:
        text = (WORKFLOWS / workflow).read_text()
        pr = text.split("  pull_request:\n", 1)[1].split("  workflow_dispatch:", 1)[0]
        return {
            line.strip().removeprefix("- ")
            for line in pr.splitlines()
            if line.strip().startswith("- ")
        }

    def test_only_the_tiers_an_event_can_change_rerun_on_it(self) -> None:
        code = {"opened", "synchronize", "reopened"}
        labels = {"labeled", "unlabeled"}
        self.assertEqual(self.pr_events("ci.yml"), code)
        self.assertEqual(
            self.pr_events("ci-ready.yml"), code | {"ready_for_review"} | labels
        )
        self.assertEqual(self.pr_events("ci-full.yml"), code | labels)

    def test_every_tier_workflow_runs_on_main_and_by_hand(self) -> None:
        for workflow in CALLERS.values():
            text = (WORKFLOWS / workflow).read_text()
            self.assertIn("  push:\n    branches: [main]\n", text)
            self.assertIn("  workflow_dispatch:\n", text)
        task = (ROOT / ".mise/tasks/ci/commit-hygiene-fixes").read_text()
        self.assertIn('for workflow in CI "CI ready" "CI full"; do', task)

    def test_each_caller_gates_its_tier_as_a_unit_and_names_its_check(self) -> None:
        for tier, workflow in CALLERS.items():
            text = (WORKFLOWS / workflow).read_text()
            self.assertIn(f"          CI_TIER: {tier}\n", text)
            self.assertIn(
                f"  {tier}:\n    needs: plan\n    if: needs.plan.outputs.selected == 'true'\n"
                f"    uses: ./.github/workflows/tier-{tier}.yml\n",
                text,
            )
            self.assertIn(f"    name: all-good ({tier})\n", text)
            self.assertIn(f"          RESULT: ${{{{ needs.{tier}.result }}}}\n", text)
            self.assertIn("        run: bash .mise/tasks/ci/verdict\n", text)

    def test_tier_workflows_compose_exactly_the_catalog_jobs(self) -> None:
        for tier, workflow in CALLERS.items():
            expected = tier_jobs(tier)
            self.assertTrue(expected, tier)
            tier_text = (WORKFLOWS / f"tier-{tier}.yml").read_text()
            called = re.findall(
                r"uses: \./\.github/workflows/job-([a-z-]+)\.yml", tier_text
            )
            self.assertEqual(called, expected)
            declared = re.findall(
                r"^      ([a-z-]+):\n        description:", tier_text, re.MULTILINE
            )
            self.assertEqual(declared, ["secrets-available", *expected])
            caller_text = (WORKFLOWS / workflow).read_text()
            passed = re.findall(
                r"^      ([a-z-]+): \$\{\{ needs\.plan\.outputs\.\1 \}\}",
                caller_text,
                re.MULTILINE,
            )
            self.assertEqual(passed, expected)

    def test_every_job_has_one_workflow_named_by_its_variant(self) -> None:
        for job in JOBS:
            text = (WORKFLOWS / f"job-{job}.yml").read_text()
            self.assertIn(
                f"jobs:\n  {job}:\n    name: ${{{{ matrix.variant }}}}\n", text
            )
            self.assertIn("      matrix: ${{ fromJSON(inputs.matrix) }}\n", text)
            self.assertNotIn("CI_SECRETS", text)


class VerdictScriptTest(unittest.TestCase):
    """Verify that only successful planning and tier execution can pass."""

    def verdict(self, **env: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", str(ROOT / ".mise/tasks/ci/verdict")],
            env={
                "PATH": os.environ["PATH"],
                "PLAN_RESULT": "success",
                "SELECTED": "false",
                "RESTATE": "false",
                "RESULT": "skipped",
                **env,
            },
            check=False,
            capture_output=True,
            text=True,
        )

    def test_selected_tier_passes_only_when_its_jobs_succeeded(self) -> None:
        self.assertEqual(self.verdict(SELECTED="true", RESULT="success").returncode, 0)
        for result in ["failure", "cancelled", "skipped", ""]:
            with self.subTest(result=result):
                self.assertNotEqual(
                    self.verdict(SELECTED="true", RESULT=result).returncode, 0
                )

    def test_unrequired_tier_passes_only_when_its_jobs_were_skipped(self) -> None:
        self.assertEqual(self.verdict().returncode, 0)
        for result in ["success", "failure", "cancelled", ""]:
            with self.subTest(result=result):
                self.assertNotEqual(self.verdict(RESULT=result).returncode, 0)

    def test_failed_or_missing_plan_never_passes(self) -> None:
        self.assertNotEqual(self.verdict(PLAN_RESULT="failure").returncode, 0)
        self.assertNotEqual(self.verdict(PLAN_RESULT="skipped").returncode, 0)
        self.assertNotEqual(self.verdict(SELECTED="").returncode, 0)
        self.assertNotEqual(self.verdict(SELECTED="", RESULT="success").returncode, 0)

    def test_reuses_coverage_verified_by_the_planner(self) -> None:
        self.assertEqual(self.verdict(RESTATE="true").returncode, 0)
        self.assertNotEqual(
            self.verdict(RESTATE="true", PLAN_RESULT="failure").returncode, 0
        )


if __name__ == "__main__":
    unittest.main()
