import json
import tempfile
import unittest
from pathlib import Path

from compare import compare
from test_performance import write_run


class ComparisonTest(unittest.TestCase):
    def test_run_medians_across_builds_and_implementations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, cpu in enumerate((100, 200, 900)):
                write_run(root / "before" / str(index), cpu=cpu)
                write_run(
                    root / "after" / str(index),
                    cpu=cpu * 0.9,
                    implementation="compose-declarative",
                    artifact="new-build",
                )
            result = compare(root / "before", root / "after")
            self.assertEqual(result["metrics"]["cpu_ms"]["baseline"]["median"], 200)
            self.assertAlmostEqual(result["metrics"]["cpu_ms"]["change_percent"], -10)
            single = compare(root / "before/0", root / "after/0")
            self.assertAlmostEqual(single["metrics"]["cpu_ms"]["change_percent"], -10)
            path = root / "after/0/app.log"
            path.write_text(path.read_text().replace("[400,800,2]", "[500,800,2]"))
            with self.assertRaisesRegex(ValueError, "viewport"):
                compare(root / "before", root / "after")

    def test_missing_or_changed_environment_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("before", "after"):
                write_run(root / name)
                path = root / name / "metadata.json"
                metadata = json.loads(path.read_text()) | {
                    "platform": "web",
                    "browser": "123",
                }
                path.write_text(json.dumps(metadata))
            compare(root / "before", root / "after")
            path = root / "after/metadata.json"
            for key, value in (
                ("os", "updated OS"),
                ("os", None),
                ("browser", "124"),
                ("browser", None),
            ):
                changed = metadata | {key: value}
                path.write_text(json.dumps(changed))
                with self.assertRaisesRegex(ValueError, key):
                    compare(root / "before", root / "after")
            for key in ("os", "browser"):
                for name in ("before", "after"):
                    (root / name / "metadata.json").write_text(
                        json.dumps(metadata | {key: None})
                    )
                with self.assertRaisesRegex(ValueError, key):
                    compare(root / "before", root / "after")
