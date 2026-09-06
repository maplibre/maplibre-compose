"""Select PR coverage while keeping dependency, main, and manual runs complete."""

from __future__ import annotations

import json
import os
import pathlib

FULL_LABEL = "ci:full"
MATRIX = pathlib.Path(__file__).with_name("desktop_matrix.json")


def plan(event_name: str, event: dict) -> dict:
    tier = "full"
    if event_name == "pull_request":
        # Missing PR metadata is an error, not permission to run fewer tests.
        pr = event["pull_request"]
        labels = {label["name"] for label in pr["labels"]}
        # The PR author stays the same when a maintainer labels or reruns it.
        dependabot = pr["user"]["login"] == "dependabot[bot]"
        if not dependabot and FULL_LABEL not in labels:
            tier = "draft" if pr["draft"] else "ready"

    runners = {
        "draft": {"ubuntu-24.04"},
        "ready": {"ubuntu-24.04", "macos-26", "windows-2022"},
    }
    desktop = [
        row
        for row in json.loads(MATRIX.read_text())
        if tier == "full" or row["runner"] in runners[tier]
    ]
    expected = dict.fromkeys(
        ["plan", "hygiene", "android", "ios", "ios-device", "js", "desktop", "docs"],
        "success",
    )
    if tier == "draft":
        expected["ios"] = "skipped"
    return {"tier": tier, "desktop": {"include": desktop}, "expected": expected}


def main() -> None:
    selection = plan(
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
    with pathlib.Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
        print(f"CI tier: **{selection['tier']}**", file=summary)
        print(
            "\nDesktop runners: "
            + ", ".join(row["runner"] for row in selection["desktop"]["include"]),
            file=summary,
        )
        print(
            "\nAdd `ci:full` to a pull request to run every platform, including on drafts.",
            file=summary,
        )


if __name__ == "__main__":
    main()
