"""Select PR coverage while keeping dependency, main, and manual runs complete."""

from __future__ import annotations

import json
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
