"""Displayed Android map frames, independent of app-window and render-event callbacks."""

import json
import shlex
import subprocess
import time
from pathlib import Path


def timestamps(text):
    """SurfaceFlinger: desired presentation, actual presentation, buffer-ready time."""
    result = set()
    for line in text.splitlines():
        parts = line.split()
        if len(parts) == 3:
            try:
                _, actual, _ = map(int, parts)
            except ValueError:
                continue
            # Zero is an empty slot; INT64_MAX is an unsignalled presentation fence.
            if 0 < actual < 2**63 - 1:
                result.add(actual)
    return sorted(result)


class MapPresentation:
    """Poll the map layer's finite history during warm-up and measurement, without clearing it."""

    def __init__(self, adb, package):
        self.adb = adb
        self.package = package
        self.layer = None
        self.frames = set()
        self.previous = set()
        self.next_poll = 0
        self.gaps = []
        self.refresh_periods = []

    def shell(self, *args):
        return subprocess.check_output(
            [*self.adb, "shell", "dumpsys", "SurfaceFlinger", *args], text=True
        )

    def poll(self):
        if time.monotonic() < self.next_poll:
            return
        self.next_poll = time.monotonic() + 0.25
        if self.layer is None:
            # Recent Android versions wrap the layer name in RequestedLayerState{...}.
            names = [
                line.removeprefix("RequestedLayerState{").split(" parentId=")[0]
                for line in self.shell("--list").splitlines()
            ]
            layers = [
                name
                for name in names
                if self.package + "/" in name and name.startswith("SurfaceView")
            ]
            # BLAST devices list both the container and its buffer layer.
            layers = [layer for layer in layers if "(BLAST)" in layer] or layers
            if not layers:
                return  # The map is still being created.
            if len(layers) != 1:
                raise RuntimeError("Expected one map SurfaceView layer")
            self.layer = layers[0]
        dump = self.shell("--latency", shlex.quote(self.layer))
        batch = set(timestamps(dump))
        if batch:
            self.refresh_periods.append([max(batch), int(dump.splitlines()[0])])
        # Reject discontinuities in the measured window; warm-up can reset the history.
        if self.previous and batch and not batch.intersection(self.previous):
            self.gaps.append([max(self.previous), min(batch)])
        self.frames.update(batch)
        if batch:
            self.previous = batch

    def save(self, output):
        self.next_poll = 0
        self.poll()
        Path(output, "presentation.json").write_text(
            json.dumps(
                {
                    "source": "surfaceflinger",
                    "layer": self.layer,
                    "gaps_ns": self.gaps,
                    "refresh_periods_ns": self.refresh_periods,
                    "presented_ns": sorted(self.frames),
                },
                indent=2,
            )
            + "\n"
        )
