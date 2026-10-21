"""Select the jobs a CI run needs from the units that Rubberstamp planned."""

from __future__ import annotations

import json
import os
import pathlib

CATALOG = pathlib.Path(__file__).with_name("jobs.json")


def catalog() -> list[dict]:
    """Every job, with a variant only when it runs in multiple configurations.

    A job without a unit runs on every event.
    """
    return json.loads(CATALOG.read_text())


def job_name(row: dict) -> str:
    return row["job"] + (f" / {row['variant']}" if "variant" in row else "")


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


def selected(units: list[str] | None) -> list[dict]:
    """The catalog rows for the planned units, or for every unit when None."""
    return [
        row
        for row in catalog()
        if "unit" not in row or units is None or row["unit"] in units
    ]


def outputs(rows: list[dict], secrets: bool) -> dict:
    """The plan job's outputs: the jobs that run and each matrix job's matrix."""
    # Only jobs with multiple configurations need a matrix.
    matrices = {
        row["job"]: {"variant": [], "include": []}
        for row in catalog()
        if "variant" in row
    }
    for row in rows:
        if "variant" in row:
            matrix = matrices[row["job"]]
            matrix["variant"].append(row["variant"])
            matrix["include"].append(
                {key: value for key, value in row.items() if key not in ("job", "unit")}
            )
    return {
        "jobs": list(dict.fromkeys(row["job"] for row in rows)),
        "secrets": secrets,
        **matrices,
    }


def main() -> None:
    event = json.loads(pathlib.Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    # The plan action reports every unit whenever it has no plan to follow.
    if os.environ["RUBBERSTAMP_ALL"] == "false":
        units = json.loads(os.environ["RUBBERSTAMP_UNITS"])
    else:
        units = None
    rows = selected(units)
    secrets = secrets_available(
        os.environ["GITHUB_EVENT_NAME"], event, os.environ["GITHUB_REPOSITORY"]
    )
    with pathlib.Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        for key, value in outputs(rows, secrets).items():
            if isinstance(value, bool):
                encoded = str(value).lower()
            else:
                encoded = json.dumps(value, separators=(",", ":"))
            print(f"{key}={encoded}", file=output)
    jobs = ", ".join(job_name(row) for row in rows)
    with pathlib.Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
        print(f"\nJobs: {jobs}", file=summary)


if __name__ == "__main__":
    main()
