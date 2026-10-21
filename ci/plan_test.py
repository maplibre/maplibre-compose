"""Exercise job selection from Rubberstamp units and the CI workflow's gates."""

from __future__ import annotations

import json
import os
import pathlib
import re
import subprocess
import tempfile
import textwrap
import unittest

from ci.plan import catalog, job_name, outputs, secrets_available, selected

ROOT = pathlib.Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"
RUBBERSTAMP = ROOT / ".config/rubberstamp.yml"
REPO = "maplibre/maplibre-compose"


def pr_event(*, author: str = "contributor", fork: bool = False) -> dict:
    return {
        "pull_request": {
            "user": {"login": author},
            "head": {"repo": {"full_name": "someone/fork" if fork else REPO}},
        },
    }


def configured_units() -> set[str]:
    """The unit ids in the Rubberstamp config's `ci.units` mapping."""
    section = RUBBERSTAMP.read_text().split("\nci:\n", 1)[1]
    units = section.split("\n  units:\n", 1)[1]
    return set(re.findall(r"^    ([a-z0-9][a-z0-9._-]*):$", units, re.MULTILINE))


def names(rows: list[dict]) -> set[str]:
    return {job_name(row) for row in rows}


def unit_names(*units: str) -> set[str]:
    return {job_name(row) for row in catalog() if row.get("unit", "") in units}


ALWAYS = unit_names("")


class UnitTest(unittest.TestCase):
    def test_every_unit_owns_jobs_and_every_job_but_hygiene_has_a_unit(self) -> None:
        cataloged = {row["unit"] for row in catalog() if "unit" in row}
        self.assertEqual(cataloged, configured_units())
        self.assertEqual(ALWAYS, {"hygiene"})

    def test_rubberstamp_counts_passes_from_the_ci_workflow(self) -> None:
        self.assertIn(
            "\n  workflow: .github/workflows/ci.yml\n", RUBBERSTAMP.read_text()
        )


class PlanTest(unittest.TestCase):
    def test_every_unit_runs_every_job(self) -> None:
        self.assertEqual(names(selected(None)), names(catalog()))

    def test_planned_units_run_only_their_jobs_and_hygiene(self) -> None:
        for units in (["ios"], ["android-oldest", "desktop-linux-arm64"]):
            with self.subTest(units=units):
                self.assertEqual(names(selected(units)), ALWAYS | unit_names(*units))

    def test_no_planned_units_run_only_hygiene(self) -> None:
        result = outputs(selected([]), secrets=True)
        self.assertEqual(result["jobs"], ["hygiene"])
        for job in ("android", "ios", "desktop"):
            self.assertEqual(result[job], {"variant": [], "include": []})

    def test_unknown_units_select_nothing(self) -> None:
        self.assertEqual(names(selected(["linux-x64"])), ALWAYS)

    def test_outputs_name_each_job_once_and_build_its_matrix(self) -> None:
        result = outputs(selected(["ios", "ios-oldest", "android"]), secrets=False)
        self.assertEqual(result["jobs"], ["hygiene", "ios-device", "ios", "android"])
        self.assertFalse(result["secrets"])
        self.assertEqual(result["ios"]["variant"], ["latest", "15.5"])
        self.assertEqual(
            result["ios"]["include"],
            [{"variant": "latest"}, {"variant": "15.5", "ios-version": "15.5"}],
        )
        self.assertEqual(result["android"]["variant"], ["36"])
        self.assertEqual(result["desktop"], {"variant": [], "include": []})

    def test_secrets_only_for_trusted_heads(self) -> None:
        def available(event_name: str, event: dict) -> bool:
            return secrets_available(event_name, event, REPO)

        self.assertTrue(available("push", {}))
        self.assertTrue(available("pull_request", pr_event()))
        self.assertFalse(available("pull_request", pr_event(fork=True)))
        self.assertFalse(available("pull_request", pr_event(author="dependabot[bot]")))
        deleted_fork = pr_event()
        deleted_fork["pull_request"]["head"]["repo"] = None
        self.assertFalse(available("pull_request", deleted_fork))
        self.assertTrue(available("workflow_dispatch", {}))
        for secrets in ["false", False]:
            event = {"inputs": {"secrets": secrets}}
            self.assertFalse(available("workflow_dispatch", event))


class PlanCommandTest(unittest.TestCase):
    def run_plan(self, all_units: str, units: str) -> tuple[dict, str]:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            event = root / "event.json"
            output = root / "output"
            summary = root / "summary"
            event.write_text(json.dumps(pr_event()))
            subprocess.run(
                ["bash", str(ROOT / ".mise/tasks/ci/plan")],
                cwd=root,
                env={
                    "PATH": os.environ["PATH"],
                    "GITHUB_EVENT_NAME": "pull_request",
                    "GITHUB_EVENT_PATH": str(event),
                    "GITHUB_REPOSITORY": REPO,
                    "GITHUB_OUTPUT": str(output),
                    "GITHUB_STEP_SUMMARY": str(summary),
                    "RUBBERSTAMP_ALL": all_units,
                    "RUBBERSTAMP_UNITS": units,
                },
                check=True,
                capture_output=True,
                text=True,
            )
            values = dict(
                line.split("=", 1) for line in output.read_text().splitlines()
            )
            return values, summary.read_text()

    def test_task_runs_outside_checkout_and_writes_the_planned_jobs(self) -> None:
        values, summary = self.run_plan("false", '["desktop-macos"]')
        self.assertEqual(json.loads(values["jobs"]), ["hygiene", "desktop"])
        self.assertEqual(values["secrets"], "true")
        self.assertEqual(json.loads(values["desktop"])["variant"], ["macos-arm64"])
        self.assertEqual(json.loads(values["android"])["variant"], [])
        self.assertIn("Jobs: hygiene, desktop / macos-arm64", summary)

    def test_any_answer_but_a_plan_runs_every_unit(self) -> None:
        for all_units in ("true", ""):
            with self.subTest(all_units=all_units):
                values, _ = self.run_plan(all_units, "[]")
                self.assertEqual(
                    set(json.loads(values["jobs"])), {row["job"] for row in catalog()}
                )


class WorkflowTest(unittest.TestCase):
    text = (WORKFLOWS / "ci.yml").read_text()
    jobs = text.split("\njobs:\n", 1)[1]

    def job(self, name: str) -> str:
        match = re.search(
            rf"^  {name}:\n(.*?)(?=^  [a-z-]+:$|\Z)",
            self.jobs,
            re.MULTILINE | re.DOTALL,
        )
        assert match, name
        return match[1]

    def test_workflow_declares_the_catalog_jobs(self) -> None:
        declared = set(re.findall(r"^  ([a-z-]+):$", self.jobs, re.MULTILINE))
        expected = {row["job"] for row in catalog()}
        self.assertEqual(declared, {"plan", "all-good", *expected})

    def test_every_job_but_hygiene_waits_for_its_selection(self) -> None:
        for job in {row["job"] for row in catalog()} - {"hygiene"}:
            with self.subTest(job=job):
                self.assertIn(
                    "    needs: plan\n"
                    f"    if: contains(fromJSON(needs.plan.outputs.jobs), '{job}')\n",
                    self.job(job),
                )
        self.assertNotIn("needs:", self.job("hygiene"))

    def test_matrix_jobs_receive_their_planned_matrix(self) -> None:
        for job in {row["job"] for row in catalog() if "variant" in row}:
            with self.subTest(job=job):
                self.assertIn(
                    f"      {job}: ${{{{ steps.plan.outputs.{job} }}}}\n",
                    self.job("plan"),
                )
                self.assertIn(
                    f"      matrix: ${{{{ fromJSON(needs.plan.outputs.{job}) }}}}\n",
                    self.job(job),
                )

    def test_required_check_needs_the_plan_and_every_job(self) -> None:
        required = self.job("all-good")
        needs = required.split("    needs:\n", 1)[1].split("\n    timeout", 1)[0]
        self.assertEqual(
            set(re.findall(r"^      - ([a-z-]+)$", needs, re.MULTILINE)),
            {"plan", *(row["job"] for row in catalog())},
        )

    def verdict(self, plan: str, **results: str) -> int:
        """Run the required check's script as GitHub would for these results."""
        script = textwrap.dedent(self.job("all-good").split("        run: |\n", 1)[1])
        needs = {"plan": {"result": plan}} | {
            job: {"result": result} for job, result in results.items()
        }
        return subprocess.run(
            ["bash", "-e", "-c", script],
            env={
                "PATH": os.environ["PATH"],
                "PLAN": plan,
                "RESULTS": json.dumps(needs),
            },
            capture_output=True,
            check=False,
        ).returncode

    def test_required_check_passes_only_planned_successful_or_skipped_jobs(
        self,
    ) -> None:
        self.assertEqual(self.verdict("success", hygiene="success"), 0)
        self.assertEqual(self.verdict("success", hygiene="success", abi="skipped"), 0)
        for result in ("failure", "cancelled"):
            with self.subTest(result=result):
                self.assertNotEqual(
                    self.verdict("success", hygiene="success", abi=result), 0
                )
        for plan in ("failure", "cancelled", "skipped"):
            with self.subTest(plan=plan):
                self.assertNotEqual(self.verdict(plan, hygiene="success"), 0)

    def test_retry_follows_the_ci_workflow(self) -> None:
        retry = (WORKFLOWS / "ci-retry.yml").read_text()
        self.assertIn("    workflows:\n      - CI\n    types:", retry)


if __name__ == "__main__":
    unittest.main()
