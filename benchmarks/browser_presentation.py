"""Read actual Chromium canvas presentations, which can repeat an old map texture."""

import json
from pathlib import Path


def capture(directory, config):
    path = Path(directory) / "browser-trace.json"
    if not path.exists():
        return None
    trace = json.loads(path.read_text())
    if trace.get("dataLossOccurred"):
        raise ValueError("Chromium presentation trace lost events")
    events = trace["traceEvents"]
    starts = [e for e in events if e["name"] == "MAP_BENCHMARK_APP_PRESENTATION_START"]
    ends = [e for e in events if e["name"] == "MAP_BENCHMARK_APP_PRESENTATION_END"]
    if len(starts) != 1 or len(ends) != 1 or starts[0]["pid"] != ends[0]["pid"]:
        raise ValueError("Expected one browser presentation window")
    start = starts[0]
    # Ending on a delayed UI callback must not extend the requested window.
    end = min(ends[0]["ts"], start["ts"] + config["durationMs"] * 1000)
    pid = start["pid"]
    tracks = {
        e["id2"]["local"]
        for e in events
        if e["pid"] == pid
        and e["name"] == "FrameSequenceTrackerV3"
        and e["ph"] == "b"
        and e.get("args", {}).get("name") == "CanvasAnimation"
    }
    # Nested Frame slices end on presentation feedback. RAF and JSAnimation tracks
    # also exist, but their frames do not identify a new canvas drawing.
    times = sorted(
        {
            e["ts"] * 1000
            for e in events
            if e["pid"] == pid
            and e["name"].strip() == "Frame"
            and e["ph"] == "e"
            and e.get("id2", {}).get("local") in tracks
        }
    )
    periods = [
        [e["ts"] * 1000, e["args"]["args"]["interval_us"] * 1000]
        for e in events
        if e["pid"] == pid
        and e["name"] == "Scheduler::BeginFrame"
        and "interval_us" in e.get("args", {}).get("args", {})
    ]
    return {
        "source": "chromium-canvas-presented",
        "layer": "app canvas",
        "gaps_ns": [],
        "presented_ns": times,
        "refresh_periods_ns": periods,
        "window": {"start_ns": start["ts"] * 1000, "end_ns": end * 1000},
    }
