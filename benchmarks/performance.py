"""Android execution and presentation metrics, restricted to the scenario's trace interval."""

import json
import re
from pathlib import Path

from analyze import distribution
from perfetto.trace_processor import TraceProcessor

PACKAGE = "org.maplibre.compose.demoapp"


def analyze_performance(directory):
    directory = Path(directory)
    logs = (directory / "app.log").read_text()
    if "MAP_BENCHMARK DONE" not in logs or "FATAL EXCEPTION" in logs:
        raise ValueError("App did not finish cleanly")
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
        uid = json.loads((directory / "metadata.json").read_text()).get(
            "uid", runs[0]["uid"]
        )
        cpu = query(f"""SELECT thread.name AS thread, SUM(MIN(s.ts+s.dur,{end})-MAX(s.ts,{start}))/1e6 AS cpu_ms
          FROM sched s JOIN thread USING(utid) WHERE thread.upid={upid}
          AND s.dur>0 AND s.ts<{end} AND s.ts+s.dur>{start} GROUP BY thread.utid ORDER BY cpu_ms DESC""")
        if not cpu:
            raise ValueError("CPU scheduling data is absent")
        frames = query(f"""SELECT layer_name, present_type, jank_type, dur/1e6 AS duration_ms
          FROM actual_frame_timeline_slice WHERE upid={upid} AND ts>={start} AND ts<{end} AND dur>0""")
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
        reports = re.findall(r"MAP_BENCHMARK WINDOW (\{[^\n]+\})", logs)
        window = {"available": False, "reason": "No Window FrameMetrics report"}
        if len(reports) == 1:
            window = json.loads(reports[0])
            if window["lost_reports"]:
                raise ValueError("Window FrameMetrics reports were dropped")
            metrics = [
                (float(a), float(b))
                for a, b in re.findall(r"MAP_BENCHMARK FRAME ([\d.-]+) ([\d.-]+)", logs)
            ]
            window.update(
                available=bool(metrics),
                frames=len(metrics),
                total_ms=distribution([a for a, b in metrics]),
                gpu_ms=distribution([b for a, b in metrics if b >= 0]),
            )
        result = {
            "schema": 1,
            "duration_ms": (end - start) / 1e6,
            "cpu_ms": sum(t["cpu_ms"] for t in cpu),
            "cpu_threads": cpu,
            "frame_timeline": {
                "available": bool(frames),
                "scope": "Only layers represented by Android FrameTimeline; TX entries are transactions, not a count of displayed map buffers",
                "layers": presentation,
            },
            "window": window,
            "gpu": gpu_result,
        }
        (directory / "performance.json").write_text(json.dumps(result, indent=2) + "\n")
        return result
