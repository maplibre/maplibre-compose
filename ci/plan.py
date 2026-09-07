"""Select the job variants one CI tier runs from the GitHub event."""

from __future__ import annotations

import json
import os
import pathlib

FULL_LABEL = "ci:full"
TIERS = ("draft", "ready", "full")
# The caller job in each tier's workflow, which prefixes every check name.
CALLERS = {"draft": "ci", "ready": "ready", "full": "full"}
JOBS = ("plan", "hygiene", "android", "ios", "ios-device", "js", "desktop", "docs")
CATALOG = pathlib.Path(__file__).with_name("jobs.json")


def variants() -> list[dict]:
    """Every job variant, each tagged with the tier that introduces it."""
    rows = json.loads(CATALOG.read_text())
    for row in rows:
        if row["job"] not in JOBS[1:] or row["tier"] not in TIERS:
            raise ValueError(f"unknown job or tier in {row['job']} {row['variant']}")
    return rows


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


def plan(tier: str, event_name: str, event: dict) -> dict:
    if tier not in TIERS:
        raise ValueError(f"unknown CI tier {tier!r}")
    restate = False
    if event_name == "pull_request":
        # Each tier's workflow runs only the variants that tier introduces, and
        # only when the event is what made them necessary. A tier that was
        # already required before the event has a verdict on this commit, which
        # the required check restates rather than replaces.
        pr = event["pull_request"]
        before = state_before(event)
        selected = {tier} if required(tier, pr) else set()
        if selected and before is not None and required(tier, before):
            selected = set()
            restate = True
    else:
        # Main and manual runs cover every variant in a single workflow.
        selected = set(TIERS)
    # `variant` is the only matrix dimension, so GitHub names each job after it
    # and leaves the row's other fields out of the name.
    matrices: dict[str, dict] = {
        job: {"variant": [], "include": []} for job in JOBS[1:]
    }
    for row in variants():
        if row["tier"] in selected:
            matrix = matrices[row["job"]]
            matrix["variant"].append(row["variant"])
            matrix["include"].append(
                {key: value for key, value in row.items() if key not in ("job", "tier")}
            )
    run = [job for job, matrix in matrices.items() if matrix["include"]]
    expected = {"plan": "success"}
    for job, matrix in matrices.items():
        expected[job] = "success" if matrix["include"] else "skipped"
    return {
        "tier": tier,
        "check": f"{CALLERS[tier]} / all-good",
        "restate": restate,
        "run": run,
        "expected": expected,
        **matrices,
    }


def main() -> None:
    selection = plan(
        os.environ["CI_TIER"],
        os.environ["GITHUB_EVENT_NAME"],
        json.loads(pathlib.Path(os.environ["GITHUB_EVENT_PATH"]).read_text()),
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
        f"{job} ({variant})"
        for job in selection["run"]
        for variant in selection[job]["variant"]
    ]
    with pathlib.Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
        print(f"CI tier: **{selection['tier']}**", file=summary)
        if selection["restate"]:
            jobs = "none; this event did not change what the tier requires"
        else:
            jobs = ", ".join(names) if names else "none required"
        print(f"\nJobs: {jobs}", file=summary)
        print(
            "\nAdd `ci:full` to a pull request to run every platform, including on drafts.",
            file=summary,
        )


if __name__ == "__main__":
    main()
