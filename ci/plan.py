"""Select the job variants one CI tier runs from the GitHub event."""

from __future__ import annotations

import json
import os
import pathlib
import subprocess

FULL_LABEL = "ci:full"
TIERS = ("draft", "ready", "full")
JOBS = ("hygiene", "android", "ios", "ios-device", "js", "desktop", "docs")
CATALOG = pathlib.Path(__file__).with_name("jobs.json")


def variants() -> list[dict]:
    """Every job variant, each tagged with the tier that introduces it."""
    rows = json.loads(CATALOG.read_text())
    for row in rows:
        if row["job"] not in JOBS or row["tier"] not in TIERS:
            raise ValueError(f"unknown job or tier in {row['job']} {row['variant']}")
    return rows


def tier_jobs(tier: str) -> list[str]:
    """The jobs with at least one variant in the tier, in workflow order."""
    return [
        job
        for job in JOBS
        if any(r["job"] == job and r["tier"] == tier for r in variants())
    ]


def required(tier: str, pr: dict) -> bool:
    """Whether a pull request in the given state needs this tier's variants."""
    # Missing PR metadata is an error, not permission to run fewer tests.
    labels = {label["name"] for label in pr["labels"]}
    # The PR author stays the same when a maintainer labels or reruns it.
    if pr["user"]["login"] == "dependabot[bot]" or FULL_LABEL in labels:
        return True
    return tier == "draft" or (tier == "ready" and not pr["draft"])


def state_before(event: dict) -> dict | None:
    """The PR as it was before an event that changed only its state.

    Code events (opened, synchronize, reopened) have no earlier state on this
    commit, so nothing has run for it yet.
    """
    pr = event["pull_request"]
    if event.get("action") == "ready_for_review":
        return {**pr, "draft": True}
    if event.get("action") == "labeled":
        added = event["label"]["name"]
        labels = [label for label in pr["labels"] if label["name"] != added]
        return {**pr, "labels": labels}
    if event.get("action") == "unlabeled":
        return {**pr, "labels": [*pr["labels"], event["label"]]}
    return None


def secrets_available(event_name: str, event: dict, repository: str) -> bool:
    """Whether the run may use repository secrets, such as upload tokens."""
    if event_name == "push":
        return True
    if event_name == "pull_request":
        pr = event["pull_request"]
        head_repo = pr["head"].get("repo") or {}
        return (
            pr["user"]["login"] != "dependabot[bot]"
            and head_repo.get("full_name") == repository
        )
    if event_name == "workflow_dispatch":
        return str(event.get("inputs", {}).get("secrets", "true")).lower() != "false"
    return False


def plan(tier: str, event_name: str, event: dict, repository: str) -> dict:
    if tier not in TIERS:
        raise ValueError(f"unknown CI tier {tier!r}")
    restate = False
    if event_name == "pull_request":
        # State-only events may reuse this commit's successful platform jobs.
        # main() verifies that coverage before leaving the tier unselected.
        pr = event["pull_request"]
        before = state_before(event)
        selected = required(tier, pr)
        if selected and before is not None and required(tier, before):
            selected = False
            restate = True
    else:
        # Main and manual runs trigger every tier's workflow.
        selected = True
    # `variant` is the only matrix dimension, so GitHub names each job after it
    # and leaves the row's other fields out of the name.
    matrices: dict[str, dict] = {job: {"variant": [], "include": []} for job in JOBS}
    for row in variants():
        if selected and row["tier"] == tier:
            matrix = matrices[row["job"]]
            matrix["variant"].append(row["variant"])
            matrix["include"].append(
                {key: value for key, value in row.items() if key not in ("job", "tier")}
            )
    return {
        "tier": tier,
        "check": f"all-good ({tier})",
        "selected": selected,
        "restate": restate,
        "secrets": secrets_available(event_name, event, repository),
        **matrices,
    }


def resolve_restatement(selection: dict, check_runs: list[dict]) -> dict:
    """Reuse only complete, successful coverage; otherwise run the whole tier."""
    if not selection["restate"]:
        return selection
    expected = {
        f"{selection['tier']} / {row['job']} / {row['variant']}"
        for row in variants()
        if row["tier"] == selection["tier"]
    }
    # Different workflow runs have different check suites. Keep the newest
    # check across suites too, so an older success cannot hide a later failure.
    latest = {
        check["name"]: check
        for check in sorted(check_runs, key=lambda check: check["id"])
    }
    successful = {
        check["name"]
        for check in latest.values()
        if check["status"] == "completed" and check["conclusion"] == "success"
    }
    if expected <= successful:
        return selection
    # A pending producer can be replaced by this event in GitHub's concurrency
    # queue. Every surviving run must be able to provide the missing coverage.
    selected = plan(selection["tier"], "workflow_dispatch", {}, "")
    return {**selected, "secrets": selection["secrets"]}


def latest_checks(repository: str, head_sha: str) -> list[dict]:
    """Read the latest check for each job on this exact PR head, across pages."""
    result = subprocess.run(
        [
            "gh",
            "api",
            "--method",
            "GET",
            "--paginate",
            "--slurp",
            f"repos/{repository}/commits/{head_sha}/check-runs",
            "-f",
            "filter=latest",
            "-f",
            "per_page=100",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return [check for page in json.loads(result.stdout) for check in page["check_runs"]]


def main() -> None:
    event = json.loads(pathlib.Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    selection = plan(
        os.environ["CI_TIER"],
        os.environ["GITHUB_EVENT_NAME"],
        event,
        os.environ["GITHUB_REPOSITORY"],
    )
    if selection["restate"]:
        selection = resolve_restatement(
            selection,
            latest_checks(
                os.environ["GITHUB_REPOSITORY"], event["pull_request"]["head"]["sha"]
            ),
        )
    with pathlib.Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        for key, value in selection.items():
            if isinstance(value, str):
                encoded = value
            elif isinstance(value, bool):
                encoded = str(value).lower()
            else:
                encoded = json.dumps(value, separators=(",", ":"))
            print(f"{key}={encoded}", file=output)
    names = [
        f"{job} / {variant}" for job in JOBS for variant in selection[job]["variant"]
    ]
    with pathlib.Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
        print(f"CI tier: **{selection['tier']}**", file=summary)
        if selection["restate"]:
            jobs = "none; every platform job already succeeded on this commit"
        else:
            jobs = ", ".join(names) if names else "none required"
        print(f"\nJobs: {jobs}", file=summary)
        print(
            "\nAdd `ci:full` to a pull request to run every platform, including on drafts.",
            file=summary,
        )


if __name__ == "__main__":
    main()
