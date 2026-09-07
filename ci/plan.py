"""Select the job variants one CI tier runs from the GitHub event."""

from __future__ import annotations

import json
import os
import pathlib

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
        # A tier's workflow runs its variants only when the event is what made
        # them necessary. A tier that was already required before the event
        # has a verdict on this commit, which the required check restates
        # rather than replaces.
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


def main() -> None:
    selection = plan(
        os.environ["CI_TIER"],
        os.environ["GITHUB_EVENT_NAME"],
        json.loads(pathlib.Path(os.environ["GITHUB_EVENT_PATH"]).read_text()),
        os.environ["GITHUB_REPOSITORY"],
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
