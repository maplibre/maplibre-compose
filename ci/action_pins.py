"""Read, verify, and rewrite the third-party GitHub Actions pins catalog."""

from __future__ import annotations

import pathlib
import re
from typing import NamedTuple

CATALOG = pathlib.Path(".github/workflows/action-pins.yml")

# `uses: owner/repo@<40-hex-sha> # vX.Y.Z`, the only form this repository allows.
PINNED = re.compile(
    r"^\s*(?:-\s+)?uses:\s*"
    r"(?P<action>[\w.-]+/[\w./-]+?)@(?P<sha>[0-9a-f]{40})"
    r"\s+#\s*(?P<version>\S+)\s*$"
)
# Any `uses:` at all, so unpinned references are reported rather than skipped.
USES = re.compile(r"^\s*(?:-\s+)?uses:\s*(?P<reference>\S+)")


class Pin(NamedTuple):
    action: str
    sha: str
    version: str

    @property
    def reference(self) -> str:
        return f"{self.action}@{self.sha} # {self.version}"


def _parse(path: pathlib.Path) -> list[tuple[int, Pin | str]]:
    """Yield (line number, Pin) for pinned uses and (line number, raw) otherwise."""
    found: list[tuple[int, Pin | str]] = []
    for number, line in enumerate(path.read_text().splitlines(), 1):
        pinned = PINNED.match(line)
        if pinned:
            found.append((number, Pin(**pinned.groupdict())))
            continue
        loose = USES.match(line)
        if loose and not loose["reference"].startswith("./"):
            found.append((number, loose["reference"]))
    return found


def catalog(root: pathlib.Path) -> dict[str, Pin]:
    """Map each action name to its pin, as declared by the catalog."""
    pins: dict[str, Pin] = {}
    for _, entry in _parse(root / CATALOG):
        if isinstance(entry, Pin):
            pins[entry.action] = entry
    return pins


def consumers(root: pathlib.Path) -> list[pathlib.Path]:
    """Every workflow and composite action that may reference a pinned action."""
    paths = [
        path
        for pattern in ("*.yml", "*.yaml")
        for path in sorted((root / ".github" / "workflows").glob(pattern))
        if path != root / CATALOG
    ]
    paths.extend(
        path
        for pattern in ("**/action.yml", "**/action.yaml")
        for path in sorted((root / ".github" / "actions").glob(pattern))
    )
    return paths


# The SHA and version comment; indent, `- `, and `uses:` stay as they were.
_PIN_REF = re.compile(r"@[0-9a-f]{40}\s+#\s*\S+")


def _apply_pin(line: str, pin: Pin) -> str:
    return _PIN_REF.sub(f"@{pin.sha} # {pin.version}", line, count=1)


def check_pins(root: pathlib.Path) -> list[str]:
    """Report every action reference that disagrees with the catalog."""
    pins = catalog(root)
    problems: list[str] = []
    used: set[str] = set()

    for path in consumers(root):
        location = path.relative_to(root).as_posix()
        for number, entry in _parse(path):
            if isinstance(entry, str):
                problems.append(
                    f"{location}:{number}: {entry} is not pinned to a commit SHA "
                    "with a `# version` comment"
                )
                continue
            used.add(entry.action)
            expected = pins.get(entry.action)
            if expected is None:
                problems.append(
                    f"{location}:{number}: {entry.action} is missing from "
                    f"{CATALOG.as_posix()}"
                )
            elif entry != expected:
                problems.append(
                    f"{location}:{number}: {entry.action} is pinned to "
                    f"{entry.sha} ({entry.version}), but "
                    f"{CATALOG.as_posix()} pins {expected.sha} ({expected.version})"
                )

    for action in sorted(set(pins) - used):
        problems.append(
            f"{CATALOG.as_posix()}: {action} is no longer used; remove the pin"
        )
    return problems


def fix_pins(root: pathlib.Path) -> list[pathlib.Path]:
    """Rewrite consumer pins that disagree with the catalog.

    Unpinned uses, actions missing from the catalog, and unused catalog
    entries stay as they are. Those still fail ``check_pins``.
    """
    pins = catalog(root)
    changed: list[pathlib.Path] = []
    for path in consumers(root):
        original = path.read_text()
        rewritten: list[str] = []
        dirty = False
        for line in original.splitlines(keepends=True):
            pinned = PINNED.match(line)
            if pinned:
                current = Pin(**pinned.groupdict())
                expected = pins.get(current.action)
                if expected is not None and current != expected:
                    line = _apply_pin(line, expected)
                    dirty = True
            rewritten.append(line)
        if dirty:
            path.write_text("".join(rewritten))
            changed.append(path)
    return changed
