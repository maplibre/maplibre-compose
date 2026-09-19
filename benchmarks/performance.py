"""Process CPU counters and Android trace metrics for the scenario measurement interval."""

import json
import math
import re
from pathlib import Path

from analyze import distribution, logged_config, read_run
from perfetto.trace_processor import TraceProcessor


def frame_budget_ms(logs):
    """The vsync budget implied by the logged configuration, defaulting to 60 Hz."""
    config = logged_config(logs)
    if config:
        parts = config.split(",")
        if len(parts) > 2 and parts[2] != "default":
            try:
                cap = int(parts[2])
            except ValueError as error:
                raise ValueError(f"Invalid logged FPS cap: {parts[2]}") from error
            if cap <= 0:
                raise ValueError(f"Invalid logged FPS cap: {parts[2]}")
            return 1000 / cap
    return 1000 / 60


def parse_frame_json(text, description):
    try:
        return json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError(f"Malformed {description}: {error}") from error


def finite_number(value):
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
    )


def frames_metrics(logs):
    """In-app frame intervals and native render timings from the frame recorder logs."""
    reports = re.findall(r"MAP_BENCHMARK FRAMESTATS (\{[^\n]+\})", logs)
    batches = re.findall(r"MAP_BENCHMARK FRAMETIMES (\[[^\n]+\])", logs)
    if not reports and not batches:
        return {"available": False, "reason": "No in-app frame timing report"}
    if len(reports) != 1:
        raise ValueError("Expected one in-app frame timing report")
    summary = parse_frame_json(reports[0], "in-app frame timing summary")
    if not isinstance(summary, dict):
        # Corrupt log data, so a ValueError matches this module's other integrity errors.
        raise ValueError("Invalid in-app frame timing summary")  # noqa: TRY004
    records = [
        sample
        for index, batch in enumerate(batches)
        for sample in parse_frame_json(batch, f"in-app frame timing batch {index}")
    ]
    if any(not isinstance(record, dict) for record in records):
        raise ValueError("Invalid in-app frame timing record")
    if summary.get("frames") != len(records):
        raise ValueError("In-app frame timing log is incomplete")
    duration = summary.get("duration_ms")
    if duration is not None and (not finite_number(duration) or duration < 0):
        raise ValueError("Invalid in-app frame timing summary")
    intervals = [record.get("interval_ms") for record in records]
    if not intervals or any(
        not finite_number(value) or value < 0 for value in intervals
    ):
        raise ValueError("Invalid in-app frame interval")
    budget = frame_budget_ms(logs)
    encoding = [
        record["encoding_ms"]
        for record in records
        if record.get("encoding_ms") is not None
    ]
    rendering = [
        record["rendering_ms"]
        for record in records
        if record.get("rendering_ms") is not None
    ]
    draw_calls = [
        record["draw_calls"]
        for record in records
        if record.get("draw_calls") is not None
    ]
    modes = {}
    for record in records:
        if record.get("mode") is not None:
            modes[record["mode"]] = modes.get(record["mode"], 0) + 1
    native = bool(encoding) or bool(rendering)
    return {
        "available": True,
        "scope": "In-app map render loop; excludes display presentation, which video capture covers where available",
        "frames": len(records),
        "duration_ms": summary.get("duration_ms"),
        "assumed_vsync_ms": budget,
        "interval_ms": distribution(intervals),
        "jank_over_1_5x": sum(value > 1.5 * budget for value in intervals),
        "jank_over_2x": sum(value > 2.0 * budget for value in intervals),
        "native_render_stats": native,
        "encoding_ms": distribution(encoding),
        "rendering_ms": distribution(rendering),
        "draw_calls": distribution(draw_calls),
        "modes": modes,
    }


def window_metrics(logs, start, end):
    reports = re.findall(r"MAP_BENCHMARK WINDOW (\{[^\n]+\})", logs)
    window = {"available": False, "reason": "No Window FrameMetrics report"}
    if len(reports) == 1:
        window = json.loads(reports[0])
        if window["lost_reports"]:
            raise ValueError("Window FrameMetrics reports were dropped")
        metrics = [
            tuple(map(int, record.split(",")))
            for batch in re.findall(r"MAP_BENCHMARK FRAMES (\S+)", logs)
            for record in batch.split(";")
        ]
        if len(metrics) != window["frames"]:
            raise ValueError("Window frame metric log is incomplete")
        if any(
            len(frame) != 4 or (frame[0] <= 0 and frame[0] != -1) or frame[1] < 0
            for frame in metrics
        ):
            raise ValueError("Invalid Window FrameMetrics record")
        # Include only complete frames within the same interval as scheduled CPU work.
        frames = [
            frame
            for frame in metrics
            if frame[0] > 0 and start <= frame[0] and frame[0] + frame[1] <= end
        ]
        deadlines = [frame for frame in frames if frame[3] >= 0]
        window.update(
            available=bool(frames),
            reported_frames=len(metrics),
            frames=len(frames),
            deadline_frames=len(deadlines),
            missed_deadlines=sum(
                total > deadline for _, total, _, deadline in deadlines
            )
            if deadlines
            else None,
            total_ms=distribution([total / 1e6 for _, total, _, _ in frames]),
            gpu_ms=distribution([gpu / 1e6 for _, _, gpu, _ in frames if gpu >= 0]),
        )
        if metrics and all(frame[0] == -1 for frame in metrics):
            window["reason"] = "Window frame timestamps are unavailable"
    return window


def process_cpu_metrics(logs):
    """A performance report from logs alone; CPU is unavailable on platforms without a counter."""
    records = re.findall(r"MAP_BENCHMARK CPU (\S+)", logs)
    frames = frames_metrics(logs)
    if len(records) > 1:
        raise ValueError("Expected one process CPU measurement")
    cpu = float(records[0]) if records else None
    if cpu is not None and (not math.isfinite(cpu) or cpu < 0):
        raise ValueError("Invalid process CPU measurement")
    if cpu is None and not frames.get("available"):
        return None
    return {
        "schema": 1,
        "cpu_ms": cpu,
        "cpu_scope": "Process CPU counter delta across all app threads during the measurement interval"
        if cpu is not None
        else "No process CPU adapter for this platform",
        "frames": frames,
        "gpu": {"available": False, "reason": "No GPU adapter for this platform"},
        "window": {
            "available": False,
            "reason": "No presentation timing adapter for this platform",
        },
    }


def analyze_performance(directory):
    directory = Path(directory)
    metadata, logs, _ = read_run(directory)
    with TraceProcessor(trace=str(directory / "trace.perfetto-trace")) as trace:

        def query(sql):
            return [vars(row) for row in trace.query(sql)]

        runs = query(
            "SELECT s.ts, s.dur, p.upid, p.uid FROM slice s JOIN process_track pt ON s.track_id=pt.id JOIN process p USING(upid) WHERE s.name='MapBenchmark' AND s.dur>0"
        )
        if len(runs) != 1 or not 11e9 <= runs[0]["dur"] <= 15e9:
            raise ValueError(
                "Trace must contain exactly one complete measurement interval"
            )
        start, end = runs[0]["ts"], runs[0]["ts"] + runs[0]["dur"]
        upid = runs[0]["upid"]
        uid = int(metadata.get("uid", runs[0]["uid"]) or 0)
        cpu = query(f"""SELECT thread.name AS thread, SUM(MIN(s.ts+s.dur,{end})-MAX(s.ts,{start}))/1e6 AS cpu_ms
          FROM sched s JOIN thread USING(utid) WHERE thread.upid={upid}
          AND s.dur>0 AND s.ts<{end} AND s.ts+s.dur>{start} GROUP BY thread.utid ORDER BY cpu_ms DESC""")
        if not cpu:
            raise ValueError("CPU scheduling data is absent")
        frames = query(f"""SELECT layer_name, present_type, jank_type, dur/1e6 AS duration_ms
          FROM actual_frame_timeline_slice WHERE upid={upid} AND ts>={start} AND ts<{end} AND ts+dur<={end} AND dur>=0""")
        layers = {}
        for frame in frames:
            layers.setdefault(frame["layer_name"], []).append(frame)
        presentation = {
            layer: {
                "events": len(items),
                "late_present": sum(i["present_type"] == "Late Present" for i in items),
                "dropped": sum(i["present_type"] == "Dropped Frame" for i in items),
                "app_deadline_missed": sum(
                    "App Deadline Missed" in (i["jank_type"] or "") for i in items
                ),
                "prediction_error": sum(
                    "Prediction Error" in (i["jank_type"] or "") for i in items
                ),
                "duration_ms": distribution([i["duration_ms"] for i in items]),
            }
            for layer, items in layers.items()
        }
        # The work-period tracepoint attributes GPU active time to a UID; it is not universally available.
        query("INCLUDE PERFETTO MODULE android.gpu.work_period")
        gpu = query(f"""SELECT s.ts, s.dur, s.thread_dur AS active_ns, t.gpu_id FROM slice s
          JOIN android_gpu_work_period_track t ON s.track_id=t.id
          WHERE t.uid={uid} AND t.uid>0 AND s.ts>={start} AND s.ts+s.dur<={end} AND s.dur>0""")
        gpu_result = {
            "available": False,
            "reason": "Driver did not emit attributable GPU work periods; CPU submission time is not GPU time",
        }
        if gpu:
            if any(
                g["active_ns"] is None or not 0 <= g["active_ns"] <= g["dur"]
                for g in gpu
            ):
                raise ValueError("Invalid GPU active duration")
            gpu_result = {
                "available": True,
                "scope": "app UID, fully contained work periods; boundary periods excluded",
                "active_ms": sum(g["active_ns"] for g in gpu) / 1e6,
                "reported_period_ms": sum(g["dur"] for g in gpu) / 1e6,
                "periods": gpu,
            }
        loss = query(
            "SELECT name, value FROM stats WHERE severity = 'data_loss' AND value > 0"
        )
        if loss:
            raise ValueError(f"Trace lost data: {loss}")
        window = window_metrics(logs, start, end)
        result = {
            "schema": 1,
            "duration_ms": (end - start) / 1e6,
            "cpu_ms": sum(t["cpu_ms"] for t in cpu),
            "cpu_threads": cpu,
            "frames": frames_metrics(logs),
            "frame_timeline": {
                "available": bool(frames),
                "scope": "Only layers represented by Android FrameTimeline; TX entries are transactions, not a count of displayed map buffers",
                "layers": presentation,
            },
            "window": window,
            "gpu": gpu_result,
        }
        return result
