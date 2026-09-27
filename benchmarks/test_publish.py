import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from performance import analyze
from publish import (
    PREFIX,
    cases_meta,
    plan,
    publishable,
    series_values,
    summarize,
    sync,
    table,
)
from test_performance import write_run

BUILD = {
    "commit": "c" * 40,
    "dirty": False,
    "fixtures": "f1",
    "dependency_versions": {},
}


def snapshot(commit, scope, cpu):
    with tempfile.TemporaryDirectory() as directory:
        reports = []
        for index, value in enumerate((cpu, cpu * 2, cpu * 4)):
            root = Path(directory) / str(index)
            write_run(root, cpu=value, build=BUILD | {"commit": commit})
            reports.append(analyze(root))
    entry = {
        "implementation": "compose-imperative",
        "config": reports[0]["config"],
        "viewport": reports[0]["viewport"],
        "build": reports[0]["build"],
        "metrics": summarize(reports),
        "runs": reports,
    }
    return {
        "commit": commit,
        "scope": scope,
        "platform": "android",
        "device": scope,
        "measuredAt": "2026-09-26T00:00:00+00:00",
        "cases": {"paint-points": {"compose": entry}},
    }


class Store:
    def __init__(self):
        self.data = {}

    def read(self, key):
        return json.loads(json.dumps(self.data[key])) if key in self.data else None

    def write(self, items, immutable):
        self.data.update(json.loads(json.dumps(items)))


class PublishTest(unittest.TestCase):
    def test_plan_runs_classic_only_where_the_platform_has_one(self):
        android = list(plan("android"))
        desktop = list(plan("desktop"))
        self.assertEqual({kind for _, kind, _ in desktop}, {"compose"})
        self.assertIn("classic", {kind for _, kind, _ in android})
        for name, kind, config in android:
            implementation = json.loads(config)["implementation"]
            self.assertEqual(
                kind == "classic", implementation == "classic-android", name
            )
        # Map return is only hosted on the phones.
        self.assertEqual(
            [name for name, _, _ in desktop],
            [name for name in cases_meta() if name != "map-return"],
        )
        self.assertIn("map-return", [name for name, _, _ in android])
        tracked = {
            "image-registration",
            "image-preparation",
            "image-cycle",
            "style-publication",
            "style-overlay",
            "overlay-update",
            "runtime-startup",
        }
        self.assertTrue(tracked <= {name for name, _, _ in android})
        self.assertIn(
            ("image-cycle", "classic"), [(name, kind) for name, kind, _ in android]
        )
        for platform in ("android", "ios", "desktop", "web"):
            runtimes = [
                (kind, config)
                for name, kind, config in plan(platform)
                if name == "runtime-startup"
            ]
            self.assertEqual(
                [kind for kind, _ in runtimes], [] if platform == "web" else ["compose"]
            )

    def test_lifecycle_series_keep_submission_and_cleanup_spreads(self):
        reports = [
            {
                "workload": {
                    "submission_ms": {"p50": value},
                    "close_ms": {"p50": value * 2},
                    "close_completion_ms": {"p50": value * 10},
                }
            }
            for value in (1, 3, 5)
        ]
        entry = {"metrics": summarize(reports)}
        self.assertEqual(
            series_values(entry),
            {
                "submission_p50_ms": 3,
                "submission_p50_ms.min": 1,
                "submission_p50_ms.max": 5,
                "close_p50_ms": 6,
                "close_p50_ms.min": 2,
                "close_p50_ms.max": 10,
                "close_completion_p50_ms": 30,
                "close_completion_p50_ms.min": 10,
                "close_completion_p50_ms.max": 50,
            },
        )
        output = table({"runtime-startup": {"compose": entry}})
        self.assertIn("submit p50 ms", output)
        self.assertIn("close p50 ms", output)
        self.assertIn("cleanup p50 ms", output)

    def test_results_stay_local_unless_the_checkout_is_clean_and_on_main(self):
        cases = snapshot("c" * 40, "pixel", 100)["cases"]
        self.assertIsNone(publishable("c" * 40, False, True, cases))
        self.assertIn("uncommitted", publishable("c" * 40, True, True, cases))
        self.assertIn("not on origin/main", publishable("c" * 40, False, False, cases))
        self.assertIn("fetched", publishable("c" * 40, False, None, cases))
        self.assertIn("built from", publishable("d" * 40, False, True, cases))

    def test_sync_orders_commits_and_aligns_every_scope(self):
        store = Store()
        commits = {
            "a" * 40: {
                "commit": "a" * 40,
                "date": "2026-09-01T00:00:00+00:00",
                "title": "first",
                "tags": [],
            },
            "b" * 40: {
                "commit": "b" * 40,
                # Later than c by instant, earlier by text: ordering must use the instant.
                "date": "2026-09-03T02:00:00+03:00",
                "title": "second",
                "tags": [],
            },
            "c" * 40: {
                "commit": "c" * 40,
                "date": "2026-09-03T00:00:00+00:00",
                "title": "third",
                "tags": [],
            },
        }

        # v9.9.9 is tagged on an unmeasured commit between b and c, so it marks c.
        def ancestor(command, check):
            order = {"0" * 40: -1, "a" * 40: 0, "b" * 40: 1, "t" * 40: 2, "c" * 40: 3}
            return Mock(returncode=0 if order[command[3]] <= order[command[4]] else 1)

        with (
            patch(
                "publish.release_tags",
                # v0.0.1 predates the timeline and stays off it.
                return_value={"v9.9.9": "t" * 40, "v0.0.1": "0" * 40},
            ),
            patch("publish.subprocess.run", side_effect=ancestor),
        ):
            for commit, scope, cpu in (
                ("c" * 40, "pixel", 100),
                ("a" * 40, "pixel", 50),
            ):
                sync(
                    store,
                    snapshot(commit, scope, cpu),
                    {"id": scope, "label": scope, "platform": "android"},
                    cases_meta(),
                    commits[commit],
                )
            # Another device measures a commit between the two, and one again.
            for commit, scope, cpu in (
                ("b" * 40, "iphone", 30),
                ("c" * 40, "pixel", 400),
            ):
                sync(
                    store,
                    snapshot(commit, scope, cpu),
                    {
                        "id": scope,
                        "label": scope,
                        "platform": "ios" if scope == "iphone" else "android",
                    },
                    cases_meta(),
                    commits[commit],
                )
        index = store.read(PREFIX + "index.json")
        self.assertEqual([c["commit"][0] for c in index["commits"]], ["a", "b", "c"])
        self.assertEqual([c["tags"] for c in index["commits"]], [[], [], ["v9.9.9"]])
        self.assertEqual({s["id"] for s in index["scopes"]}, {"pixel", "iphone"})
        self.assertIn("idle-basemap", index["cases"])
        pixel = store.read(PREFIX + "series/pixel.json")
        # Medians of (cpu, 2cpu, 4cpu) per operation over two operations; the re-run replaced c.
        self.assertEqual(
            pixel["paint-points.compose.cpu_ms_per_operation"], [50, None, 400]
        )
        self.assertEqual(
            pixel["paint-points.compose.cpu_ms_per_operation.max"], [100, None, 800]
        )
        iphone = store.read(PREFIX + "series/iphone.json")
        self.assertEqual(
            iphone["paint-points.compose.cpu_ms_per_operation"], [None, 30, None]
        )
        self.assertEqual(
            store.read(f"{PREFIX}snapshots/{'c' * 40}/pixel.json")["cases"][
                "paint-points"
            ]["compose"]["metrics"]["cpu_ms"]["median"],
            800,
        )
        self.assertIn("paint-points", table(snapshot("c" * 40, "pixel", 100)["cases"]))
        # Measuring the commit again replaces every earlier value of that device.
        with patch("publish.release_tags", return_value={}):
            empty = dict(snapshot("c" * 40, "pixel", 1), cases={})
            sync(
                store,
                empty,
                {"id": "pixel", "label": "pixel", "platform": "android"},
                cases_meta(),
                commits["c" * 40],
            )
        pixel = store.read(PREFIX + "series/pixel.json")
        self.assertEqual(
            pixel["paint-points.compose.cpu_ms_per_operation"], [50, None, None]
        )
