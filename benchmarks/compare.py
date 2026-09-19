"""Compare run medians for a code change or another implementation on the same device."""

import argparse
import json
import statistics
from pathlib import Path

from config import parse_config
from performance import read_run

METRICS = {
    "cpu_ms": ("cpu_ms",),
    "operations": ("workload", "operations"),
    "duration_ms": ("workload", "duration_ms"),
    "submission_p50_ms": ("workload", "submission_ms", "p50"),
    "completion_p50_ms": ("workload", "completion_ms", "p50"),
    "completion_p95_ms": ("workload", "completion_ms", "p95"),
    "render_events": ("frames", "frames"),
    "encoding_p50_ms": ("frames", "encoding_ms", "p50"),
    "encoding_p95_ms": ("frames", "encoding_ms", "p95"),
    "rendering_p50_ms": ("frames", "rendering_ms", "p50"),
    "rendering_p95_ms": ("frames", "rendering_ms", "p95"),
}


def compatible(first, second, implementations=False):
    a, b = first[0], second[0]
    for key in ("platform", "device", "os"):
        if a.get(key) != b.get(key):
            raise ValueError(f"Cannot compare different {key}")
    if first[1]["viewport"] != second[1]["viewport"]:
        raise ValueError("Cannot compare different viewports")
    left, right = parse_config(a["config"]), parse_config(b["config"])
    if implementations:
        left.pop("implementation")
        right.pop("implementation")
    if left != right:
        raise ValueError("Cannot compare different workloads")


def load_group(directory):
    directory = Path(directory)
    paths = (
        [directory / "metadata.json"]
        if (directory / "metadata.json").exists()
        else sorted(directory.glob("*/metadata.json"))
    )
    if not paths:
        raise ValueError(f"No benchmark runs in {directory}")
    runs = [read_run(path.parent) for path in paths]
    for run in runs[1:]:
        compatible(runs[0], run)
        if runs[0][0]["artifact"] != run[0]["artifact"]:
            raise ValueError("Build artifacts differ within a group")
    return runs


def metric(report, path):
    for key in path:
        report = report.get(key) if isinstance(report, dict) else None
    return report


def summarize(values):
    return {"median": statistics.median(values), "min": min(values), "max": max(values)}


def compare(baseline, candidate):
    before, after = load_group(baseline), load_group(candidate)
    compatible(before[0], after[0], implementations=True)
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
            if a["median"]
            else None,
        }
    return {
        "baseline": str(baseline),
        "candidate": str(candidate),
        "baseline_config": before[0][0]["config"],
        "candidate_config": after[0][0]["config"],
        "baseline_runs": len(before),
        "candidate_runs": len(after),
        "metrics": metrics,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    print(json.dumps(compare(args.baseline, args.candidate), indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
