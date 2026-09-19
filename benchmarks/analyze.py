"""Measurements from composed pixels. Capture latency is not physical panel latency."""

import csv
import json
import re
import struct
from pathlib import Path

import cv2
import numpy as np

# Per-scenario validation. camera_motion requires the map marker to move at least 40 dp; scenarios
# whose workload does not move the camera verify markers, span, and samples only. min_coverage
# loosens the both-markers-present check where the workload legitimately blanks the map.
SCENARIO_PROFILES = {
    "animation": {"camera_motion": True},
    "setters": {"camera_motion": True},
    "input": {"camera_motion": True},
    "style-complex": {"camera_motion": True},
    "style-swap": {"camera_motion": False, "min_coverage": 0.90},
    "style-mutate": {"camera_motion": False},
    "geojson-update": {"camera_motion": False},
    "padding": {"camera_motion": True},
    "images": {"camera_motion": False},
    "resize": {"camera_motion": False},
}
DEFAULT_PROFILE = {"camera_motion": False}

# Defaults of each scenario's params object. Keep in sync with the @Serializable params classes in
# demo-app/common/src/commonMain/kotlin/org/maplibre/compose/demoapp/benchmark/BenchmarkModels.kt.
SCENARIO_DEFAULT_PARAMS = {
    "animation": {},
    "setters": {},
    "input": {},
    "style-complex": {"layers": 8, "features": 2000, "sources": 2},
    "style-swap": {"intervalMs": 1500, "count": 8, "layers": 6, "features": 1500},
    "style-mutate": {"rateHz": 8.0, "pairs": 4},
    "geojson-update": {"rateHz": 4.0, "features": 5000},
    "padding": {"amplitudeDp": 120.0, "periodMs": 2000},
    "images": {"count": 8, "intervalMs": 500, "sizePx": 32},
    "resize": {"periodMs": 2000, "minPercent": 50},
}


def profile_for(config):
    return SCENARIO_PROFILES.get(config.split(",")[0], DEFAULT_PROFILE)


def expanded_params(config):
    """A configuration's params object with its scenario's defaults filled in."""
    fields = config.split(",", 4)
    defaults = SCENARIO_DEFAULT_PARAMS.get(fields[0], {})
    text = fields[4] if len(fields) > 4 else ""
    if text in ("", "{}"):
        return dict(defaults)
    return {**defaults, **json.loads(text)}


def config_matches(requested, logged):
    """
    Compares a requested configuration with the one a START line logged.

    Missing and default parameters are expanded, so equivalent spellings compare equal while a
    default request is still rejected against different logged parameters.
    """
    requested_fields = requested.split(",", 4)
    logged_fields = logged.split(",", 4)
    if requested_fields[:4] != logged_fields[:4]:
        return False
    return expanded_params(requested) == expanded_params(logged)


def configs_equal(first, second):
    """True when two requested configurations describe the same scenario and parameters."""
    return config_matches(first, second) and config_matches(second, first)


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
    if metadata["config"].startswith("input,"):
        sequences = [int(i) for i in re.findall(r"MAP_BENCHMARK INPUT (\d+) ", logs)]
        done = int(re.search(r"MAP_BENCHMARK DONE (\d+)", logs)[1])
        if len(sequences) < 5 or sequences != list(range(1, done + 1)):
            raise ValueError(
                "Missing input events; at least five complete steps required"
            )
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
    is_input = metadata["config"].startswith("input,")
    cap = metadata["config"].split(",")[2]
    minimum_samples = 100 if cap == "default" else max(10, min(100, int(cap) * 10))
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
            for mask in masks:
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
            if len(points) == 4:
                rows.append([timestamp, *points])
                if len(rows) == 1:
                    cv2.imwrite(str(directory / "first-frame.png"), frame)
    finally:
        capture.release()
    if boot_times is not None and len(boot_times) != frame_index:
        raise ValueError("Video and clock metadata have different frame counts")
    profile = profile_for(metadata["config"])
    if len(rows) < (10 if is_input else minimum_samples) or len(
        rows
    ) < active * profile.get("min_coverage", 0.98):
        raise ValueError(f"Insufficient marker coverage: {len(rows)}/{active}")
    data = np.asarray(rows)
    intervals = np.diff(data[:, 0]) / 1e6
    measurement_end = gate_end if is_input or minimum_samples < 100 else data[-1, 0]
    if (
        not np.isfinite(data).all()
        or (intervals < 0).any()
        or measurement_end is None
        or measurement_end - data[0, 0] < 11e9
    ):
        raise ValueError("Invalid timestamps or truncated measurement")
    if profile["camera_motion"] and np.ptp(data[:, 1]) / density < 40:
        raise ValueError("Map marker did not move")
    separation = np.linalg.norm(data[:, 1:3] - data[:, 3:5], axis=1)
    result = {
        "schema": 1,
        "samples": len(rows),
        "coverage": len(rows) / active,
        "separation_px": distribution(separation),
        "separation_dp": distribution(separation / density),
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
        writer.writerow(("capture_ns", "map_x", "map_y", "overlay_x", "overlay_y"))
        writer.writerows(rows)
    (directory / "visual.json").write_text(json.dumps(result, indent=2) + "\n")
    return result
