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


def read_run(directory):
    directory = Path(directory)
    metadata = json.loads((directory / "metadata.json").read_text())
    logs = (directory / "app.log").read_text()
    if (
        "MAP_BENCHMARK DONE" not in logs
        or "MAP_BENCHMARK ERROR" in logs
        or "FATAL EXCEPTION" in logs
    ):
        raise ValueError("Benchmark failed or did not finish")
    config = parse_config(record(logs, "START"))
    if config != parse_config(metadata["config"]):
        raise ValueError("App ran a different benchmark configuration")
    viewport = record(logs, "VIEWPORT")
    if len(viewport) != 3 or any(not math.isfinite(v) or v <= 0 for v in viewport):
        raise ValueError("Invalid benchmark viewport")
    work = record(logs, "WORKLOAD")
    operations = work["operations"]
    duration = work["duration_ms"]
    if type(operations) is not int or operations < (
        0 if config["workload"] == "idle" else 1
    ):
        raise ValueError("Workload submitted no operations")
    if not config["durationMs"] <= duration <= config["durationMs"] + 10000:
        raise ValueError("Invalid workload duration")
    for label, key in (("SUBMISSIONS", "submission"), ("COMPLETIONS", "completion")):
        values = samples(logs, label)
        if len(values) != work[key + "_count"] or len(values) > operations:
            raise ValueError("Incomplete operation timings")
        work[key + "_ms"] = distribution(values)
    expected = {
        "style": "style-ready",
        "source-latency": "rendered-feature-revision",
    }.get(config["workload"])
    if expected and (
        work["completion_signal"] != expected or work["completion_count"] != operations
    ):
        raise ValueError("Missing workload completion measurements")
    summary = record(logs, "FRAMESTATS")
    frames = samples(logs, "FRAMETIMES")
    if not frames and config["workload"] not in {"idle", "recompose"}:
        raise ValueError("Redraw workload emitted no render events")
    if len(frames) != summary["frames"]:
        raise ValueError("Incomplete render statistics")
    for key in ("encoding_ms", "rendering_ms", "draw_calls"):
        summary[key] = distribution(
            [frame[key] for frame in frames if frame.get(key) is not None]
        )
    cpu = re.findall(r"MAP_BENCHMARK CPU (\S+)", logs)
    if len(cpu) > 1 or (not cpu and metadata["platform"] != "web"):
        raise ValueError("Expected one process CPU measurement")
    cpu = float(cpu[0]) if cpu else None
    if cpu is not None:
        distribution([cpu])
    return metadata, {
        "cpu_ms": cpu,
        "viewport": viewport,
        "workload": work,
        "frames": summary,
    }


def analyze(directory):
    _, result = read_run(directory)
    (Path(directory) / "performance.json").write_text(
        json.dumps(result, indent=2, allow_nan=False) + "\n"
    )
    return result
