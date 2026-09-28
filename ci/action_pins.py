"""Check that every GitHub Actions reference is pinned, and pinned the same way.

Keeps each action on a commit SHA with a `# version` comment, and each action
repository on the same pin wherever it is used, so a Dependabot bump that
misses a workflow or composite action fails instead of merging.
"""

from __future__ import annotations

import pathlib
import re

USES = re.compile(r"^\s*(?:-\s+)?uses:\s*(?P<reference>\S+)(?P<rest>.*)$")
PINNED = re.compile(r"(?P<repo>[\w.-]+/[\w.-]+)(?:/[\w./-]+)?@(?P<sha>[0-9a-f]{40})$")
VERSION = re.compile(r"\s+#\s*(?P<version>\S+)\s*$")
PATTERNS = (
    "workflows/*.yml",
    "workflows/*.yaml",
    "actions/**/action.yml",
    "actions/**/action.yaml",
)


def check_pins(root: pathlib.Path) -> list[str]:
    """Report every unpinned reference and every pin that disagrees with another."""
    paths = sorted(
        path for pattern in PATTERNS for path in (root / ".github").glob(pattern)
    )
    problems: list[str] = []
    first: dict[str, tuple[str, str]] = {}
    for path in paths:
        relative = path.relative_to(root).as_posix()
        for number, line in enumerate(path.read_text().splitlines(), 1):
            uses = USES.match(line)
            if not uses or uses["reference"].startswith("./"):
                continue
            where = f"{relative}:{number}"
            pinned = PINNED.match(uses["reference"])
            version = VERSION.match(uses["rest"])
            if not pinned or not version:
                problems.append(
                    f"{where}: {uses['reference']} is not pinned as "
                    "owner/repo@<40-hex-sha> # <version>"
                )
                continue
            pin = f"{pinned['sha']} ({version['version']})"
            seen = first.setdefault(pinned["repo"], (pin, where))
            if seen[0] != pin:
                problems.append(
                    f"{where}: {pinned['repo']} is pinned to {pin}, "
                    f"but {seen[1]} pins {seen[0]}"
                )
    return problems
