"""Run configuration and named cases shared by the runner and comparison."""

import json
import math
from pathlib import Path

IMPLEMENTATIONS = {"compose-imperative", "compose-declarative", "classic-android"}
WORKLOADS = {
    "idle": IMPLEMENTATIONS,
    "camera": {"compose-imperative", "classic-android"},
    "animation": {"compose-imperative", "classic-android"},
    "paint": IMPLEMENTATIONS,
    "layout": IMPLEMENTATIONS,
    "layers": {"compose-declarative", "classic-android"},
    "source": IMPLEMENTATIONS,
    "source-latency": IMPLEMENTATIONS,
    "style": IMPLEMENTATIONS,
    "resize": {"compose-declarative", "classic-android"},
    "padding": {"compose-declarative", "classic-android"},
    "recompose": {"compose-declarative"},
    "images": {"compose-imperative", "classic-android"},
}
SCENES = {
    "minimal",
    "points-100",
    "points-1000",
    "points-10000",
    "route-2000",
    "basemap-sf",
}
DEFAULTS = {
    "workload": "camera",
    "scene": "points-1000",
    "implementation": "compose-imperative",
    "surface": "surface",
    "maximumFps": None,
    "layers": 1,
    "rateHz": 4.0,
    "durationMs": 12000,
}
CASES = json.loads((Path(__file__).with_name("cases.json")).read_text())


def parse_config(value):
    value = json.loads(value) if isinstance(value, str) else value
    if not isinstance(value, dict) or set(value) - set(DEFAULTS):
        raise ValueError("Expected a benchmark configuration object with known fields")
    config = DEFAULTS | value
    if config["workload"] not in WORKLOADS or config["scene"] not in SCENES:
        raise ValueError("Unknown workload or scene")
    if config["implementation"] not in WORKLOADS[config["workload"]]:
        raise ValueError("This workload does not support that implementation")
    if config["surface"] not in {"surface", "texture"}:
        raise ValueError("Unknown surface")
    for key, low, high in (
        ("layers", 1, 32),
        ("durationMs", 3000, 30000),
    ):
        if type(config[key]) is not int or not low <= config[key] <= high:
            raise ValueError(f"{key} must be an integer in {low}..{high}")
    fps = config["maximumFps"]
    if fps is not None and (type(fps) is not int or not 1 <= fps <= 240):
        raise ValueError("maximumFps must be null or 1..240")
    rate = config["rateHz"]
    if (
        type(rate) not in (float, int)
        or not math.isfinite(rate)
        or not 0.1 <= rate <= 120
    ):
        raise ValueError("rateHz must be in 0.1..120")
    if config["workload"] in {
        "paint",
        "layout",
        "layers",
        "source",
        "source-latency",
        "recompose",
    } and config["scene"] in {"minimal", "basemap-sf"}:
        raise ValueError("This workload requires a points or route scene")
    if config["workload"] == "source-latency" and config["scene"] == "route-2000":
        raise ValueError("Source completion requires point probes")
    if config["workload"] == "images" and not config["scene"].startswith("points-"):
        raise ValueError("Image registration requires point symbols")
    return config


def canonical_config(value):
    return json.dumps(
        parse_config(value), separators=(",", ":"), sort_keys=True, allow_nan=False
    )
