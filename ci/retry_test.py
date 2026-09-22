"""Exercise the retry workflow's shell with recorded GitHub job outcomes."""

from __future__ import annotations

import json
import os
import pathlib
import subprocess
import tempfile
import textwrap
import unittest

WORKFLOW = (
    pathlib.Path(__file__).resolve().parents[1] / ".github/workflows/ci-retry.yml"
)
SCRIPT = textwrap.dedent(WORKFLOW.read_text().split("        run: |\n", 1)[1])


class RetryTest(unittest.TestCase):
    def run_retry(
        self,
        conclusion: str,
        *,
        timeout: bool = False,
        extra_failure: bool = False,
        current_attempt: int = 1,
        run_conclusion: str = "failure",
        aggregate: str = "failure",
    ) -> list[str]:
        jobs = [
            {"id": 1, "name": "plan", "conclusion": "success"},
            {
                "id": 2,
                "name": "ready / desktop / macos-arm64",
                "conclusion": conclusion,
                "check_run_url": "https://api.github.com/repos/maplibre/test/check-runs/20",
            },
            {"id": 3, "name": "all-good (ready)", "conclusion": aggregate},
            {
                "id": 4,
                "name": "ready / ios / arm64",
                "conclusion": "failure" if extra_failure else "success",
            },
            {"id": 5, "name": "unused", "conclusion": "skipped"},
        ]
        annotations = [
            {"annotation_level": "failure", "message": "The operation was canceled."}
        ]
        if timeout:
            annotations.append(
                {
                    "annotation_level": "failure",
                    "message": "The job has exceeded the maximum execution time of 45m0s",
                }
            )
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "jobs.json").write_text(json.dumps({"jobs": jobs}))
            (root / "annotations.json").write_text(json.dumps(annotations))
            # Exercise the workflow itself, including its jq predicates and POST target.
            mock = """
            gh() {
              case "$*" in
                'api repos/maplibre/test/actions/runs/42 --jq .run_attempt')
                  echo "$MOCK_CURRENT_ATTEMPT" ;;
                'api repos/maplibre/test/actions/runs/42/attempts/1/jobs?per_page=100')
                  cat "$MOCK_ROOT/jobs.json" ;;
                'api https://api.github.com/repos/maplibre/test/check-runs/20/annotations?per_page=100')
                  cat "$MOCK_ROOT/annotations.json" ;;
                'api --method POST repos/maplibre/test/actions/jobs/2/rerun')
                  echo "$*" >> "$MOCK_ROOT/posts" ;;
                *) echo "Unexpected gh arguments: $*" >&2; exit 99 ;;
              esac
            }
            """
            result = subprocess.run(
                ["bash", "-c", textwrap.dedent(mock) + SCRIPT],
                env={
                    **os.environ,
                    "GITHUB_REPOSITORY": "maplibre/test",
                    "CI_RUN_ID": "42",
                    "CI_RUN_ATTEMPT": "1",
                    "CI_RUN_CONCLUSION": run_conclusion,
                    "MOCK_CURRENT_ATTEMPT": str(current_attempt),
                    "MOCK_ROOT": tmp,
                },
                check=False,
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            posts = root / "posts"
            return posts.read_text().splitlines() if posts.exists() else []

    def test_retries_one_failure_or_confirmed_timeout(self):
        for conclusion in ("failure", "cancelled", "timed_out"):
            with self.subTest(conclusion=conclusion):
                self.assertEqual(
                    len(
                        self.run_retry(
                            conclusion,
                            timeout=conclusion != "failure",
                            run_conclusion=conclusion,
                        )
                    ),
                    1,
                )

    def test_does_not_revive_intentional_cancellation(self):
        for conclusion, aggregate in (
            ("cancelled", "failure"),
            ("cancelled", "cancelled"),
            ("failure", "failure"),
        ):
            with self.subTest(conclusion=conclusion, aggregate=aggregate):
                self.assertEqual(
                    self.run_retry(
                        conclusion, aggregate=aggregate, run_conclusion="cancelled"
                    ),
                    [],
                )

    def test_keeps_single_failure_and_first_attempt_limits(self):
        for options in ({"extra_failure": True}, {"current_attempt": 2}):
            with self.subTest(options=options):
                self.assertEqual(self.run_retry("failure", **options), [])
                self.assertEqual(
                    self.run_retry("cancelled", timeout=True, **options), []
                )

    def test_requires_a_failed_aggregate_check(self):
        self.assertEqual(
            self.run_retry("cancelled", timeout=True, aggregate="skipped"), []
        )
