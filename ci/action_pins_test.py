"""Tests for the GitHub Actions pins catalog."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from ci.action_pins import CATALOG, Pin, check_pins, fix_pins

OLD = Pin(
    action="jdx/mise-action",
    sha="3c2e0cf82a5b2e5249f0d3635a4d83d0ae861518",
    version="v4.2.5",
)
NEW = Pin(
    action="jdx/mise-action",
    sha="c2a87611a18de5b3828c5652fe268e992400cb5c",
    version="v4.3.0",
)
CACHE = Pin(
    action="actions/cache",
    sha="55cc8345863c7cc4c66a329aec7e433d2d1c52a9",
    version="v6.1.0",
)


def _write(root: pathlib.Path, relative: str, text: str) -> pathlib.Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return path


def _catalog(*pins: Pin) -> str:
    steps = "\n".join(f"      - uses: {pin.reference}" for pin in pins)
    return (
        "name: Action pins\n"
        "on:\n"
        "  push:\n"
        "    branches:\n"
        "      - action-pins/never-runs\n"
        "jobs:\n"
        "  pins:\n"
        "    runs-on: ubuntu-latest\n"
        "    steps:\n"
        f"{steps}\n"
    )


def _consumer(pin: Pin, *, list_item: bool = False) -> str:
    prefix = "      - uses: " if list_item else "      uses: "
    return (
        "name: Setup\n"
        "runs:\n"
        "  using: composite\n"
        "  steps:\n"
        "    - name: Install\n"
        f"{prefix}{pin.reference}\n"
    )


def _repo(tmp: pathlib.Path, *, catalog_pins: list[Pin], consumer: str) -> pathlib.Path:
    _write(tmp, CATALOG.as_posix(), _catalog(*catalog_pins))
    _write(tmp, ".github/actions/setup-ci-deps/action.yml", consumer)
    return tmp


class CheckPinsTest(unittest.TestCase):
    def test_ok_when_consumer_matches_catalog(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = _repo(
                pathlib.Path(tmp),
                catalog_pins=[NEW],
                consumer=_consumer(NEW),
            )
            self.assertEqual(check_pins(root), [])

    def test_reports_sha_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = _repo(
                pathlib.Path(tmp),
                catalog_pins=[NEW],
                consumer=_consumer(OLD),
            )
            problems = check_pins(root)
            self.assertEqual(len(problems), 1)
            self.assertIn(OLD.sha, problems[0])
            self.assertIn(NEW.sha, problems[0])
            self.assertIn("setup-ci-deps/action.yml:6", problems[0])

    def test_reports_unpinned_use(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = _repo(
                pathlib.Path(tmp),
                catalog_pins=[NEW],
                consumer=_consumer(NEW).replace(NEW.reference, "jdx/mise-action@v4"),
            )
            problems = check_pins(root)
            self.assertTrue(any("not pinned" in problem for problem in problems))

    def test_reports_unused_catalog_pin(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = _repo(
                pathlib.Path(tmp),
                catalog_pins=[NEW, CACHE],
                consumer=_consumer(NEW),
            )
            problems = check_pins(root)
            self.assertEqual(
                problems,
                [
                    f"{CATALOG.as_posix()}: {CACHE.action} is no longer used; remove the pin"
                ],
            )


class FixPinsTest(unittest.TestCase):
    def test_rewrites_mismatched_consumer(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = _repo(
                pathlib.Path(tmp),
                catalog_pins=[NEW],
                consumer=_consumer(OLD),
            )
            changed = fix_pins(root)
            consumer = root / ".github/actions/setup-ci-deps/action.yml"
            self.assertEqual(changed, [consumer])
            self.assertEqual(consumer.read_text(), _consumer(NEW))
            self.assertEqual(check_pins(root), [])

    def test_preserves_list_item_uses(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = _repo(
                pathlib.Path(tmp),
                catalog_pins=[NEW],
                consumer=_consumer(OLD, list_item=True),
            )
            fix_pins(root)
            consumer = root / ".github/actions/setup-ci-deps/action.yml"
            self.assertEqual(consumer.read_text(), _consumer(NEW, list_item=True))

    def test_rewrites_workflow_consumers(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _write(root, CATALOG.as_posix(), _catalog(NEW))
            workflow = _write(
                root,
                ".github/workflows/ci.yml",
                "name: CI\non: push\njobs:\n  a:\n    runs-on: ubuntu-latest\n"
                "    steps:\n"
                f"      - uses: {OLD.reference}\n",
            )
            fix_pins(root)
            self.assertIn(NEW.reference, workflow.read_text())
            self.assertEqual(check_pins(root), [])

    def test_is_noop_when_already_aligned(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = _repo(
                pathlib.Path(tmp),
                catalog_pins=[NEW],
                consumer=_consumer(NEW),
            )
            consumer = root / ".github/actions/setup-ci-deps/action.yml"
            before = consumer.read_text()
            self.assertEqual(fix_pins(root), [])
            self.assertEqual(consumer.read_text(), before)

    def test_leaves_unpinned_uses(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            consumer_text = _consumer(NEW).replace(NEW.reference, "jdx/mise-action@v4")
            root = _repo(
                pathlib.Path(tmp),
                catalog_pins=[NEW],
                consumer=consumer_text,
            )
            consumer = root / ".github/actions/setup-ci-deps/action.yml"
            self.assertEqual(fix_pins(root), [])
            self.assertEqual(consumer.read_text(), consumer_text)
            self.assertTrue(
                any("not pinned" in problem for problem in check_pins(root))
            )

    def test_leaves_unused_catalog_pins(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = _repo(
                pathlib.Path(tmp),
                catalog_pins=[NEW, CACHE],
                consumer=_consumer(NEW),
            )
            catalog = root / CATALOG
            before = catalog.read_text()
            self.assertEqual(fix_pins(root), [])
            self.assertEqual(catalog.read_text(), before)
            self.assertTrue(
                any("no longer used" in problem for problem in check_pins(root))
            )

    def test_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = _repo(
                pathlib.Path(tmp),
                catalog_pins=[NEW],
                consumer=_consumer(OLD),
            )
            self.assertTrue(fix_pins(root))
            self.assertEqual(fix_pins(root), [])
            self.assertEqual(check_pins(root), [])

    def test_writes_lf_newlines(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = _repo(
                pathlib.Path(tmp),
                catalog_pins=[NEW],
                consumer=_consumer(OLD),
            )
            consumer = root / ".github/actions/setup-ci-deps/action.yml"
            consumer.write_bytes(consumer.read_bytes().replace(b"\n", b"\r\n"))
            fix_pins(root)
            data = consumer.read_bytes()
            self.assertNotIn(b"\r\n", data)
            self.assertIn(NEW.reference.encode(), data)
