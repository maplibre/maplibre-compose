"""Select the job variants one CI tier runs from the GitHub event."""

from __future__ import annotations

import json
import os
import pathlib

FULL_LABEL = "ci:full"
TIERS = ("draft", "ready", "full")
JOBS = ("plan", "hygiene", "android", "ios", "ios-device", "js", "desktop", "docs")
CATALOG = pathlib.Path(__file__).with_name("jobs.json")


def variants() -> list[dict]:
    """Every job variant, each tagged with the tier that introduces it."""
    rows = json.loads(CATALOG.read_text())
    for row in rows:
        if row["job"] not in JOBS[1:] or row["tier"] not in TIERS:
            raise ValueError(f"unknown job or tier in {row['job']} {row['variant']}")
    return rows


def required(tier: str, event_name: str, event: dict) -> bool:
    """Whether a pull request in its current state needs this tier's variants."""
    if event_name != "pull_request":
        return True
    # Missing PR metadata is an error, not permission to run fewer tests.
    pr = event["pull_request"]
    labels = {label["name"] for label in pr["labels"]}
    # The PR author stays the same when a maintainer labels or reruns it.
    if pr["user"]["login"] == "dependabot[bot]" or FULL_LABEL in labels:
        return True
    return tier == "draft" or (tier == "ready" and not pr["draft"])


def plan(tier: str, event_name: str, event: dict) -> dict:
    if tier not in TIERS:
        raise ValueError(f"unknown CI tier {tier!r}")
    if event_name == "pull_request":
        # Each tier's workflow runs only the variants that tier introduces.
        selected = {tier} if required(tier, event_name, event) else set()
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
    return {"tier": tier, "run": run, "expected": expected, **matrices}


def main() -> None:
    selection = plan(
        os.environ["CI_TIER"],
        os.environ["GITHUB_EVENT_NAME"],
        json.loads(pathlib.Path(os.environ["GITHUB_EVENT_PATH"]).read_text()),
    )
    with pathlib.Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        for key, value in selection.items():
            encoded = (
                value
                if isinstance(value, str)
                else json.dumps(value, separators=(",", ":"))
            )
            print(f"{key}={encoded}", file=output)
    names = [
        f"{job} ({variant})"
        for job in selection["run"]
        for variant in selection[job]["variant"]
    ]
    with pathlib.Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
        print(f"CI tier: **{selection['tier']}**", file=summary)
        print(
            "\nJobs: " + (", ".join(names) if names else "none required"),
            file=summary,
        )
        print(
            "\nAdd `ci:full` to a pull request to run every platform, including on drafts.",
            file=summary,
        )


if __name__ == "__main__":
    main()
