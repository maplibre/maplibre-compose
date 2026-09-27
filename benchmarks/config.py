"""Run configuration and named cases shared by the runner, comparison, and publisher.

The app validates workloads, scenes, and ranges; a rejected configuration fails the run
with a `MAP_BENCHMARK ERROR` line. The runner only needs to know which app to launch.
"""

import json
from pathlib import Path

IMPLEMENTATIONS = {
    "compose-imperative",
    "compose-declarative",
    "classic-android",
    "classic-ios",
}
DEFAULT_IMPLEMENTATION = "compose-imperative"
PRESETS = json.loads((Path(__file__).with_name("cases.json")).read_text())
CASES = {name: preset["config"] for name, preset in PRESETS.items()}
# The cases the dashboard tracks over time, in the order the page shows them.
TRACKED = {name: preset for name, preset in PRESETS.items() if preset.get("tracked")}


def parse_config(value):
    value = json.loads(value) if isinstance(value, str) else value
    if not isinstance(value, dict):
        raise TypeError("Expected a benchmark configuration object")
    config = {"implementation": DEFAULT_IMPLEMENTATION} | value
    if config["implementation"] not in IMPLEMENTATIONS:
        raise ValueError("Unknown implementation")
    return config


def canonical_config(value):
    return json.dumps(
        parse_config(value), separators=(",", ":"), sort_keys=True, allow_nan=False
    )
