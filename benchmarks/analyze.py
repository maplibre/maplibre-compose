"""Measurements from composed pixels. Capture latency is not physical panel latency."""

import csv
import json
import math
import re
import struct
from pathlib import Path

import cv2
import numpy as np
from config import configs_equal, parse_config, workload


def profile_for(config):
    config = parse_config(config)
    return {
        "camera_motion": config["overlays"] > 0
        and config["workload"] in {"camera", "animation", "input"},
        "min_coverage": 0.90 if config["workload"] == "style" else 0.98,
    }


def config_matches(requested, logged):
    return configs_equal(requested, logged)


def logged_config(logs):
    """The configuration a START line logged, or None when the log has no complete line."""
    match = re.search(r"MAP_BENCHMARK START (.+?) ([\d.]+)[ \t]*$", logs, re.MULTILINE)
    return match[1] if match else None


def distribution(values):
    values = np.asarray(values, dtype=float)
    if not len(values):
        return None
    if not np.isfinite(values).all():
        raise ValueError("Non-finite measurement")
    return dict(
        zip(
            ("p50", "p95", "p99", "max"),
            map(float, np.percentile(values, (50, 95, 99, 100))),
        )
    )


def screenrecord_timestamps(path):
    """Read AOSP screenrecord's Winscope v2 elapsed-realtime metadata (nanoseconds)."""
    data = Path(path).read_bytes()
    magic = b"#VV1NSC0PET1ME2#"
    offset = data.find(magic)
    if offset < 0:
        return None
    offset += len(magic)
    if offset + 16 > len(data):
        raise ValueError("Truncated screenrecord clock header")
    version, _realtime_offset, count = struct.unpack_from("<IqI", data, offset)
    if version != 2:
        return None  # Version 1 used a different clock; never silently treat it as boot time.
    offset += 16
    if not 1 <= count <= 100000 or offset + count * 8 > len(data):
        raise ValueError("Truncated screenrecord clock metadata")
    times = np.array(struct.unpack_from(f"<{count}Q", data, offset), dtype=np.int64)
    if (np.diff(times) <= 0).any():
        raise ValueError("Non-monotonic screenrecord clock")
    return times


def input_response(rows, events):
    """Bound each alternating step's first visible response between adjacent capture frames."""
    times = rows[:, 0]
    output = {}
    for name, column in (("map", 1), ("overlay", 3)):
        positions = rows[:, column]
        midpoint = (np.percentile(positions, 10) + np.percentile(positions, 90)) / 2
        bounds = []
        for index, (sequence, event_ns) in enumerate(events):
            deadline = (
                events[index + 1][1] if index + 1 < len(events) else times[-1] + 1
            )
            first = np.searchsorted(times, event_ns)
            if first == 0 or first == len(times):
                raise ValueError("Input falls outside calibrated capture")
            matches = positions < midpoint if sequence % 2 else positions > midpoint
            if matches[first - 1]:
                raise ValueError("Response preceded input or a step was missed")
            found = np.flatnonzero(matches & (times >= event_ns) & (times < deadline))
            if not len(found):
                raise ValueError(
                    "Input produced no visible response before the next event"
                )
            frame = found[0]
            bounds.append(
                [
                    max(0, (times[frame - 1] - event_ns) / 1e6 - 1),
                    (times[frame] - event_ns) / 1e6 + 1,
                ]
            )
        output[name] = {
            "samples": len(bounds),
            "lower_ms": distribution([b[0] for b in bounds]),
            "upper_ms": distribution([b[1] for b in bounds]),
            "bounds_ms": bounds,
        }
    # Both bounds use the same capture clock and margin. Their upper-bound difference
    # is the observed response-frame spacing, not sub-frame presentation latency.
    gaps = [
        abs(m[1] - o[1])
        for m, o in zip(output["map"]["bounds_ms"], output["overlay"]["bounds_ms"])
    ]
    output["responses_in_different_capture_frames"] = int(
        sum(gap > 0.001 for gap in gaps)
    )
    output["response_frame_gap_ms"] = distribution(gaps)
    return output


def largest_component(mask):
    """The largest connected blob in a binary mask, or None. Ignore anti-aliased stray pixels."""
    count, _, stats, centers = cv2.connectedComponentsWithStats(mask, connectivity=8)
    if count <= 1:
        return None
    index = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    return {
        "area": int(stats[index, cv2.CC_STAT_AREA]),
        "bbox": tuple(int(v) for v in stats[index, :4]),
        "center": tuple(float(v) for v in centers[index]),
    }


def measurement_gate(hsv, density):
    """A green square followed by a magenta square identifies the scenario, not the launcher."""
    corner = hsv[: hsv.shape[0] // 4, : hsv.shape[1] // 4]
    tag = largest_component(cv2.inRange(corner, (140, 90, 150), (169, 255, 255)))
    if tag is None:
        return False
    x, y, width, height = tag["bbox"]
    if not (
        10 * density <= width <= 20 * density and 10 * density <= height <= 20 * density
    ):
        return False
    left = round(x - 20 * density)
    if left < 0:
        return False
    flag = corner[y : y + height, left : left + width]
    return (
        cv2.countNonZero(cv2.inRange(flag, (40, 90, 150), (79, 255, 255)))
        >= 32 * density * density
    )


def workload_metrics(logs, required=False):
    reports = re.findall(r"MAP_BENCHMARK WORKLOAD (\{[^\n]+\})", logs)
    if not reports and not required:
        return None
    if len(reports) != 1:
        raise ValueError("Expected one workload report; rebuild the benchmark app")
    report = json.loads(reports[0])
    for label, key in (("SUBMISSIONS", "submission"), ("COMPLETIONS", "completion")):
        batches = re.findall(r"MAP_BENCHMARK " + label + r" (\[[^\n]+\])", logs)
        if key + "_count" in report:
            values = [value for batch in batches for value in json.loads(batch)]
            if len(values) != report[key + "_count"]:
                raise ValueError("Incomplete operation timing batches")
            report[key + "_ms"] = values
    duration = report.get("duration_ms")
    operations = report.get("operations")
    if (
        report.get("version") != 2
        or not isinstance(duration, (float, int))
        or not math.isfinite(duration)
        or not parse_config(logged_config(logs))["durationMs"]
        <= duration
        <= parse_config(logged_config(logs))["durationMs"] + 10000
        or type(operations) is not int
        or operations < 0
    ):
        raise ValueError("Invalid or overrun workload report")
    if workload(logged_config(logs)) not in {"idle", "input"} and operations == 0:
        raise ValueError("Workload submitted no operations")
    for key in ("submission_ms", "completion_ms"):
        values = report.get(key, [])
        if not isinstance(values, list) or any(
            type(v) not in (int, float) or not math.isfinite(v) or v < 0 for v in values
        ):
            raise ValueError("Invalid operation timings")
        if len(values) > operations:
            raise ValueError("More operation timings than submissions")
    signal = report.get("completion_signal")
    if workload(logged_config(logs)) in {"style", "source-latency"}:
        expected = (
            "style-ready"
            if workload(logged_config(logs)) == "style"
            else "rendered-feature-revision"
        )
        if signal != expected or len(report.get("completion_ms", [])) != operations:
            raise ValueError("Missing operation completion measurements")
    return report


def read_run(directory):
    directory = Path(directory)
    metadata = json.loads((directory / "metadata.json").read_text())
    logs = (directory / "app.log").read_text()
    start = re.findall(r"MAP_BENCHMARK START (.+?) ([\d.]+)[ \t]*$", logs, re.MULTILINE)
    if len(start) != 1 or not config_matches(metadata["config"], start[0][0]):
        raise ValueError("Capture does not match exactly one requested benchmark")
    if (
        "MAP_BENCHMARK CLOSED" not in logs
        or "MAP_BENCHMARK DONE" not in logs
        or "MAP_BENCHMARK ERROR" in logs
        or "FATAL EXCEPTION" in logs
    ):
        raise ValueError("Benchmark failed or did not complete shutdown")
    if workload(metadata["config"]) == "input":
        sequences = [int(i) for i in re.findall(r"MAP_BENCHMARK INPUT (\d+) ", logs)]
        done = int(re.search(r"MAP_BENCHMARK DONE (\d+)", logs)[1])
        if len(sequences) < 5 or sequences != list(range(1, done + 1)):
            raise ValueError(
                "Missing input events; at least five complete steps required"
            )
    workload_metrics(logs, required=metadata.get("schema", 0) >= 3)
    density = float(start[0][1])
    if density <= 0:
        raise ValueError("Invalid density")
    return metadata, logs, density


def analyze(directory):
    directory = Path(directory)
    metadata, logs, density = read_run(directory)
    video = directory / metadata["video"]
    boot_times = (
        screenrecord_timestamps(video) if metadata["platform"] == "android" else None
    )
    capture = cv2.VideoCapture(str(video))
    rows, active, frame_index = [], 0, 0
    gate_end = None
    is_input = workload(metadata["config"]) == "input"
    profile = profile_for(metadata["config"])
    config = parse_config(metadata["config"])
    markers = config["overlays"] > 0
    cap = config["maximumFps"]
    minimum_samples = max(
        10, min(100, (cap if cap is not None else 10) * config["durationMs"] / 1200)
    )
    if not profile["camera_motion"]:
        minimum_samples = 1  # An idle map may produce only the gate transition.
    try:
        while True:
            ok, frame = capture.read()
            if not ok:
                break
            index = frame_index
            frame_index += 1
            if boot_times is not None and index >= len(boot_times):
                raise ValueError("Video and clock metadata have different frame counts")
            timestamp = (
                boot_times[index]
                if boot_times is not None
                else capture.get(cv2.CAP_PROP_POS_MSEC) * 1e6
            )
            hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
            if not measurement_gate(hsv, density):
                if active and gate_end is None:
                    gate_end = timestamp
                continue
            active += 1
            masks = (
                cv2.inRange(hsv, (0, 120, 150), (10, 255, 255))
                | cv2.inRange(hsv, (170, 120, 150), (180, 255, 255)),
                cv2.inRange(hsv, (80, 90, 150), (100, 255, 255)),
            )
            points = []
            for mask in masks if markers else ():
                component = largest_component(mask)
                if component is None:
                    break
                _, _, width, height = component["bbox"]
                if (
                    component["area"] < 30
                    or width > frame.shape[1] * 0.4
                    or height > frame.shape[0] * 0.4
                ):
                    break
                points += list(component["center"])
            if not markers or len(points) == 4:
                rows.append([timestamp, *(points if markers else [0, 0, 0, 0])])
                if len(rows) == 1:
                    cv2.imwrite(str(directory / "first-frame.png"), frame)
    finally:
        capture.release()
    if boot_times is not None and len(boot_times) != frame_index:
        raise ValueError("Video and clock metadata have different frame counts")
    if len(rows) < (10 if is_input else minimum_samples) or len(
        rows
    ) < active * profile.get("min_coverage", 0.98):
        raise ValueError(f"Insufficient marker coverage: {len(rows)}/{active}")
    data = np.asarray(rows)
    intervals = np.diff(data[:, 0]) / 1e6
    measurement_end = gate_end
    if (
        not np.isfinite(data).all()
        or (intervals < 0).any()
        or measurement_end is None
        or measurement_end - data[0, 0] < (config["durationMs"] - 1000) * 1e6
    ):
        raise ValueError("Invalid timestamps or truncated measurement")
    if profile["camera_motion"] and np.ptp(data[:, 1]) / density < 40:
        raise ValueError("Map marker did not move")
    separation = np.linalg.norm(data[:, 1:3] - data[:, 3:5], axis=1)
    result = {
        "schema": 1,
        "samples": len(rows),
        "coverage": len(rows) / active,
        "separation_px": distribution(separation) if markers else None,
        "separation_dp": distribution(separation / density) if markers else None,
        "capture_interval_ms": distribution(intervals[intervals > 0]),
        "capture_duplicate_timestamps": int(sum(intervals == 0)),
        "input_to_captured_display": {
            "available": False,
            "reason": "Requires input scenario and a calibrated capture clock",
        },
    }
    if is_input and boot_times is not None:
        events = [
            (int(i), int(t))
            for i, t in re.findall(r"MAP_BENCHMARK INPUT (\d+) (\d+)", logs)
        ]
        result["input_to_captured_display"] = {
            "available": True,
            "clock": "elapsed_realtime",
            **input_response(data, events),
        }
    with (directory / "frames.csv").open("w") as output:
        writer = csv.writer(output)
        writer.writerow(
            ("capture_ns", "map_x", "map_y", "overlay_x", "overlay_y")
            if markers
            else ("capture_ns",)
        )
        writer.writerows(rows if markers else [[row[0]] for row in rows])
    (directory / "visual.json").write_text(json.dumps(result, indent=2) + "\n")
    return result
