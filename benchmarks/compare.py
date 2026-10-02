"""Compare run medians for a code change or another implementation on the same device."""

import argparse
import json
import statistics
from pathlib import Path

# Headline metrics first: what the library adds per operation, what the user waits for, and
# how the UI thread paces. Engine timings and counts follow for diagnosis.
METRICS = {
    "cpu_ms_per_operation": ("cpu_ms_per_operation",),
    "cpu_ms_per_second": ("cpu_ms_per_second",),
    "completion_p50_ms": ("workload", "completion_ms", "p50"),
    "close_p50_ms": ("workload", "close_ms", "p50"),
    "close_completion_p50_ms": ("workload", "close_completion_ms", "p50"),
    "completion_p95_ms": ("workload", "completion_ms", "p95"),
    "map_fps": ("map_presentation", "fps"),
    "map_frame_p95_ms": ("map_presentation", "interval_ms", "p95"),
    "map_frame_max_ms": ("map_presentation", "interval_ms", "max"),
    "map_late_percent": ("map_presentation", "late_percent"),
    "ui_missed_percent": ("ui_frames", "missed_percent"),
    "ui_frame_p95_ms": ("ui_frames", "total_ms", "p95"),
    "ui_frame_max_ms": ("ui_frames", "total_ms", "max"),
    "ui_delay_max_ms": ("ui_frames", "delay_ms", "max"),
    "ui_missed_deadlines": ("ui_frames", "missed_deadlines"),
    "ui_dropped_frames": ("ui_frames", "dropped"),
    "late_frames": ("workload", "late_frames"),
    "frame_interval_max_ms": ("workload", "frame_interval_ms", "max"),
    "frame_interval_p95_ms": ("workload", "frame_interval_ms", "p95"),
    "startup_style_ready_ms": ("startup", "style_ready_ms"),
    "startup_first_frame_ms": ("startup", "first_frame_ms"),
    "submission_p50_ms": ("workload", "submission_ms", "p50"),
    "encoding_p50_ms": ("frames", "encoding_ms", "p50"),
    "rendering_p50_ms": ("frames", "rendering_ms", "p50"),
    "cpu_ms": ("cpu_ms",),
    "operations": ("workload", "operations"),
    "render_events": ("frames", "frames"),
}


def load_group(directory):
    directory = Path(directory)
    paths = (
        [directory / "performance.json"]
        if (directory / "performance.json").exists()
        else sorted(directory.glob("*/performance.json"))
    )
    if not paths:
        raise ValueError(f"No benchmark results in {directory}")
    return [json.loads(path.read_text()) for path in paths]


def metric(report, path):
    for key in path:
        report = report.get(key) if isinstance(report, dict) else None
    return report


def summarize(values):
    return {"median": statistics.median(values), "min": min(values), "max": max(values)}


def compare(baseline, candidate):
    before, after = load_group(baseline), load_group(candidate)
    metrics = {}
    for name, path in METRICS.items():
        left = [metric(report, path) for report in before]
        right = [metric(report, path) for report in after]
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
        "baseline_settings": [
            {
                "config": run["config"],
                "viewport": run["viewport"],
                "build": run.get("build"),
            }
            for run in before
        ],
        "candidate_settings": [
            {
                "config": run["config"],
                "viewport": run["viewport"],
                "build": run.get("build"),
            }
            for run in after
        ],
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
