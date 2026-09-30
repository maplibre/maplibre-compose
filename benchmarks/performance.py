"""Read measured work from the app log. No recording or profiler is required."""

import json
import math
import re
from pathlib import Path

from config import parse_config


def distribution(values):
    if not values:
        return None
    if any(
        type(v) not in (int, float) or not math.isfinite(v) or v < 0 for v in values
    ):
        raise ValueError("Invalid measurement")
    values = sorted(values)

    def percentile(fraction):
        index = (len(values) - 1) * fraction
        lower = int(index)
        return values[lower] + (values[math.ceil(index)] - values[lower]) * (
            index - lower
        )

    return {"p50": percentile(0.5), "p95": percentile(0.95), "max": values[-1]}


def record(logs, name):
    values = re.findall(r"MAP_BENCHMARK " + name + r" (.+)", logs)
    if len(values) != 1:
        raise ValueError(f"Expected one {name} record")
    return json.loads(values[0])


def samples(logs, name):
    return [
        value
        for batch in re.findall(r"MAP_BENCHMARK " + name + r" (.+)", logs)
        for value in json.loads(batch)
    ]


def late_frames(intervals):
    """Frame intervals over one and a half times the typical one: a missed frame or worse."""
    if not intervals:
        return None
    limit = 1.5 * distribution(intervals)["p50"]
    return sum(1 for interval in intervals if interval > limit)


def ui_frames(logs):
    """Window frame timings, where the platform reports them; see BenchmarkUiFrames."""
    if "MAP_BENCHMARK UISTATS " not in logs:
        return None
    ui = record(logs, "UISTATS")
    values = samples(logs, "UIFRAMES")
    if len(values) != ui["frames"]:
        raise ValueError("Incomplete UI frame statistics")
    if not values:
        return None
    ui["total_ms"] = distribution([v["total_ms"] for v in values])
    ui["delay_ms"] = distribution([v["delay_ms"] for v in values])
    deadlines = [v for v in values if v.get("deadline_ms") is not None]
    ui["deadline_frames"] = len(deadlines)
    ui["missed_deadlines"] = sum(v["total_ms"] >= v["deadline_ms"] for v in deadlines)
    return ui


def read_run(directory):
    directory = Path(directory)
    logs = (directory / "app.log").read_text()
    if (
        "MAP_BENCHMARK DONE" not in logs
        or "MAP_BENCHMARK ERROR" in logs
        or "FATAL EXCEPTION" in logs
    ):
        raise ValueError("Benchmark failed or did not finish")
    config = parse_config(record(logs, "START"))
    viewport = record(logs, "VIEWPORT")
    startup = record(logs, "STARTUP")
    if startup is not None:
        distribution([startup["style_ready_ms"], startup["first_frame_ms"]])
    work = record(logs, "WORKLOAD")
    operations = work["operations"]
    if type(operations) is not int or operations < (
        0 if config["workload"] == "idle" else 1
    ):
        raise ValueError("Workload submitted no operations")
    for label, key in (("SUBMISSIONS", "submission"), ("COMPLETIONS", "completion")):
        values = samples(logs, label)
        if len(values) != work[key + "_count"] or len(values) > operations:
            raise ValueError("Incomplete operation timings")
        work[key + "_ms"] = distribution(values)
    for label, key in (("CLOSES", "close"), ("CLOSE_COMPLETIONS", "close_completion")):
        values = samples(logs, label)
        if len(values) != work["close_count"] or len(values) > operations:
            raise ValueError("Incomplete close timings")
        work[key + "_ms"] = distribution(values)
    if (
        config["workload"] in {"map-return", "runtime-startup"}
        and work["close_count"] != operations
    ):
        raise ValueError("Every lifecycle operation must close exactly once")
    intervals = samples(logs, "INTERVALS")
    if len(intervals) != work["frame_count"] or len(intervals) > operations:
        raise ValueError("Incomplete frame intervals")
    work["frame_interval_ms"] = distribution(intervals)
    work["late_frames"] = late_frames(intervals)
    summary = record(logs, "FRAMESTATS")
    frames = samples(logs, "FRAMETIMES")
    if not frames and config["workload"] not in {
        "idle",
        "recompose",
        "runtime-startup",
    }:
        raise ValueError("Redraw workload emitted no render events")
    if len(frames) != summary["frames"]:
        raise ValueError("Incomplete render statistics")
    for key in ("encoding_ms", "rendering_ms", "draw_calls"):
        summary[key] = distribution(
            [frame[key] for frame in frames if frame.get(key) is not None]
        )
    cpu = re.findall(r"MAP_BENCHMARK CPU (\S+)", logs)
    if len(cpu) > 1:
        raise ValueError("Multiple CPU measurements in one run")
    cpu = float(cpu[0]) if cpu else None
    if cpu is not None:
        distribution([cpu])
    return {
        "ui_frames": ui_frames(logs),
        "config": config,
        "build": record(logs, "BUILD") if "MAP_BENCHMARK BUILD " in logs else None,
        "cpu_ms": cpu,
        # Process CPU per submitted operation (per frame for frame-driven workloads) and per
        # second of the measured window. These normalize the display rate and duration away.
        "cpu_ms_per_operation": cpu / operations
        if cpu is not None and operations
        else None,
        "cpu_ms_per_second": cpu / (work["duration_ms"] / 1000)
        if cpu is not None
        else None,
        "startup": startup,
        "viewport": viewport,
        "workload": work,
        "frames": summary,
    }


def analyze(directory):
    result = read_run(directory)
    (Path(directory) / "performance.json").write_text(
        json.dumps(result, indent=2, allow_nan=False) + "\n"
    )
    return result
