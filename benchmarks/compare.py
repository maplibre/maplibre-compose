"""Compare repeated captures without treating individual frames as independent runs."""

import argparse
import json
import re
import statistics
from pathlib import Path

from analyze import distribution, read_run, workload_metrics
from config import parse_config

# Artifact identity may differ between groups, but the environment and workload may not.
ENVIRONMENT = (
    "platform",
    "device",
    "host",
    "mode",
    "trace",
    "display",
    "density",
    "fingerprint",
    "fixture",
    "viewport",
)
METRICS = {
    "submission_p50_ms": ("submission", "p50"),
    "completion_p50_ms": ("completion", "p50"),
    "completion_p95_ms": ("completion", "p95"),
    "cpu_ms": ("cpu_ms",),
    "operations": ("workload", "operations"),
    "duration_ms": ("workload", "duration_ms"),
    "encoding_p50_ms": ("frames", "encoding_ms", "p50"),
    "encoding_p95_ms": ("frames", "encoding_ms", "p95"),
    "rendering_p50_ms": ("frames", "rendering_ms", "p50"),
    "rendering_p95_ms": ("frames", "rendering_ms", "p95"),
    "window_deadline_frames": ("window", "deadline_frames"),
    "window_missed_deadlines": ("window", "missed_deadlines"),
}


def metric(report, path):
    value = report
    for key in path:
        value = value.get(key) if isinstance(value, dict) else None
    return value


def load_group(directory):
    directory = Path(directory)
    paths = sorted(directory.glob("*/metadata.json"))
    if len(paths) < 3:
        raise ValueError(
            "Each group needs at least three captures in child directories"
        )
    runs = []
    for path in paths:
        metadata, logs, _ = read_run(path.parent)
        workload = workload_metrics(logs, required=True)
        report = json.loads((path.parent / "performance.json").read_text())
        report["workload"] = workload
        report["submission"] = distribution(workload.get("submission_ms", []))
        report["completion"] = distribution(workload.get("completion_ms", []))
        scenes = re.findall(r"MAP_BENCHMARK SCENE (\{[^\n]+\})", logs)
        if len(scenes) != 1:
            raise ValueError("Expected one scene report")
        scene = json.loads(scenes[0])
        metadata["fixture"] = scene["fixtureSha256"]
        metadata["viewport"] = [
            scene[key] for key in ("viewportWidthDp", "viewportHeightDp", "density")
        ]
        runs.append((metadata, report))
    first = runs[0][0]
    for metadata, _ in runs[1:]:
        compatible(first, metadata)
        for key in (
            "apk_sha256",
            "app_sha256",
            "assets_sha256",
            "commit",
            "diff_sha256",
        ):
            if first.get(key) != metadata.get(key):
                raise ValueError(f"Artifacts differ within {directory}: {key}")
    if not (
        first.get("apk_sha256") or first.get("app_sha256") or first.get("assets_sha256")
    ):
        raise ValueError("Comparison requires an identified build artifact")
    return runs


def compatible(first, second, across_implementations=False):
    for key in ENVIRONMENT:
        if first.get(key) != second.get(key):
            raise ValueError(f"Cannot compare different {key}")
    a, b = parse_config(first["config"]), parse_config(second["config"])
    if across_implementations:
        a.pop("implementation")
        b.pop("implementation")
    if a != b:
        raise ValueError("Cannot compare different workload configurations")


def summarize(values):
    return {"median": statistics.median(values), "min": min(values), "max": max(values)}


def compare(baseline, candidate, across_implementations=False):
    before, after = load_group(baseline), load_group(candidate)
    compatible(before[0][0], after[0][0], across_implementations)
    metrics = {}
    for name, path in METRICS.items():
        left = [metric(report, path) for _, report in before]
        right = [metric(report, path) for _, report in after]
        if any(value is None for value in left + right):
            continue
        a, b = summarize(left), summarize(right)
        metrics[name] = {
            "baseline": a,
            "candidate": b,
            "change_percent": 100 * (b["median"] / a["median"] - 1)
            if a["median"] != 0
            else None,
        }
    return {
        "baseline": str(Path(baseline).resolve()),
        "candidate": str(Path(candidate).resolve()),
        "baseline_runs": len(before),
        "candidate_runs": len(after),
        "baseline_config": parse_config(before[0][0]["config"]),
        "candidate_config": parse_config(after[0][0]["config"]),
        "metrics": metrics,
        "interpretation": "Medians and ranges across runs; no significance claim. Compare operation counts alongside CPU. Render statistics are engine work, not presentation latency.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--across-implementations", action="store_true")
    args = parser.parse_args()
    print(
        json.dumps(
            compare(args.baseline, args.candidate, args.across_implementations),
            indent=2,
            allow_nan=False,
        )
    )


if __name__ == "__main__":
    main()
