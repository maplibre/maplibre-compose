"""Measure the tracked cases on one device and publish them to the benchmarks page.

Builds the apps from the checkout, runs every tracked preset for MapLibre Compose and,
where the platform has one, the classic SDK, then writes the run medians into the data
the page reads. Uploads need a clean checkout of a commit on main; otherwise the results
stay in the output directory.
"""

import argparse
import datetime
import json
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

import run as runner
from compare import METRICS, metric
from config import DEFAULT_IMPLEMENTATION, TRACKED, canonical_config

ROOT = Path(__file__).resolve().parents[1]
# The code metrics publisher owns the store that reads and writes the dashboard's data.
sys.path.insert(0, str(ROOT / "tools/code-metrics"))
from history import Bucket, Directory, git

SCHEMA_VERSION = 1
PREFIX = "benchmarks/"
CLASSIC = {"android": "classic-android", "ios": "classic-ios"}
# Metrics whose spread across repetitions the page draws as a band.
HEADLINE = [
    "cpu_ms_per_operation",
    "cpu_ms_per_second",
    "completion_p50_ms",
    "submission_p50_ms",
    "close_p50_ms",
    "close_completion_p50_ms",
    "late_frames",
    "frame_interval_p95_ms",
    "frame_interval_max_ms",
    "map_fps",
    "map_frame_p95_ms",
    "map_frame_max_ms",
    "map_late_percent",
    "ui_missed_percent",
    "ui_frame_p95_ms",
    "ui_frame_max_ms",
    "startup_first_frame_ms",
]


def plan(platform):
    """Every (case, kind, config) the publisher measures on [platform], in page order."""
    for name, preset in TRACKED.items():
        if platform not in preset.get("platforms", [platform]):
            continue
        config = preset["config"]
        compose = config.get("implementation", DEFAULT_IMPLEMENTATION)
        implementations = [("compose", compose)]
        if preset.get("classic") and platform in CLASSIC:
            implementations.append(("classic", CLASSIC[platform]))
        for kind, implementation in implementations:
            yield (
                name,
                kind,
                canonical_config(config | {"implementation": implementation}),
            )


def summarize(reports):
    """Median, minimum, and maximum of each metric over the repetitions."""
    summary = {}
    for name, path in METRICS.items():
        values = [metric(report, path) for report in reports]
        if any(value is None for value in values):
            continue
        summary[name] = {
            "median": statistics.median(values),
            "min": min(values),
            "max": max(values),
        }
    return summary


def measure(platform, device, app, output, repeat, build=True):
    """Run the plan into [output] and return the snapshot of this device's results."""
    if build:
        task = {"web": "benchmark:build:js"}.get(
            platform, f"benchmark:build:{platform}"
        )
        command = ["mise", "run", task]
        if platform == "ios" and not runner.is_simulator(device):
            command += ["--", "--device"]
        subprocess.run(command, check=True)
    contexts = {}
    cases = {}
    for name, kind, config in plan(platform):
        package = runner.app_package(config)
        if package not in contexts:
            contexts[package] = runner.setup(platform, config, device, app)
        print(f"== {name} ({kind})", flush=True)
        reports = runner.capture(
            platform, contexts[package], config, output / name / kind, repeat
        )
        cases.setdefault(name, {})[kind] = {
            "implementation": json.loads(config)["implementation"],
            "config": reports[0]["config"],
            "viewport": reports[0]["viewport"],
            "build": reports[0]["build"],
            "metrics": summarize(reports),
            "runs": reports,
        }
    return cases


def checkout():
    """The measured commit, and whether its results may be published."""
    head = git("rev-parse", "HEAD")
    dirty = bool(git("status", "--porcelain", "--untracked-files=normal"))
    try:
        subprocess.run(
            ["git", "fetch", "--quiet", "--tags", "origin", "main"], check=True
        )
    except subprocess.CalledProcessError:
        return head, dirty, None
    command = ["git", "merge-base", "--is-ancestor", head, "origin/main"]
    return head, dirty, subprocess.run(command, check=False).returncode == 0


def publishable(head, dirty, on_main, cases):
    """Why the results must stay local, or None."""
    if dirty:
        return "the checkout has uncommitted changes"
    if on_main is None:
        return "origin/main could not be fetched"
    if not on_main:
        return f"{head[:7]} is not on origin/main"
    builds = {
        json.dumps(entry["build"], sort_keys=True)
        for case in cases.values()
        for entry in case.values()
    }
    for build in builds:
        build = json.loads(build)
        if build is None:
            return "a run has no build information"
        if build["dirty"] or build["commit"] != head:
            return f"the app was built from {build['commit'][:7]}, not {head[:7]}"
    return None


def series_values(entry):
    values = {}
    for name, summary in entry["metrics"].items():
        values[name] = summary["median"]
        if name in HEADLINE:
            values[f"{name}.min"] = summary["min"]
            values[f"{name}.max"] = summary["max"]
    return values


def release_tags():
    """Each release tag and the commit it points at."""
    tags = {}
    for line in git(
        "for-each-ref",
        "--format=%(refname:short) %(objectname) %(*objectname)",
        "refs/tags/v*",
    ).splitlines():
        parts = line.split()
        tags[parts[0]] = parts[-1]
    return tags


def attach_tags(entries):
    """Mark the first measured commit at or after each release's tagged commit.

    Releases are tagged on commits nobody measured, so the page would otherwise
    never see one.
    """

    def reaches(tagged, commit):
        command = ["git", "merge-base", "--is-ancestor", tagged, commit]
        return subprocess.run(command, check=False).returncode == 0

    first = entries[0]["commit"]
    # Releases older than the first measurement are not on the timeline.
    pending = {
        tag: tagged
        for tag, tagged in release_tags().items()
        if tagged == first or not reaches(tagged, first)
    }
    for entry in entries:
        entry["tags"] = []
        for tag, tagged in list(pending.items()):
            if reaches(tagged, entry["commit"]):
                entry["tags"].append(tag)
                del pending[tag]


def instant(entry):
    return datetime.datetime.fromisoformat(entry["date"])


def sync(store, snapshot, scope, cases_meta, commit_info):
    """Merge one device's measurements of one commit into the published data."""
    index = store.read(PREFIX + "index.json")
    if index and index.get("schemaVersion") != SCHEMA_VERSION:
        raise SystemExit("The published index has another schema.")
    if not index:
        index = {
            "schemaVersion": SCHEMA_VERSION,
            "generation": time.time_ns(),
            "commits": [],
            "scopes": [],
            "cases": {},
        }
    entries = index["commits"]
    scopes = {s["id"]: s for s in index["scopes"]}
    scopes[scope["id"]] = scope
    series = {key: store.read(f"{PREFIX}series/{key}.json") or {} for key in scopes}
    # Commits are ordered by date; a commit measured for the first time takes its place
    # in every scope's series.
    position = next(
        (i for i, entry in enumerate(entries) if entry["commit"] == snapshot["commit"]),
        None,
    )
    inserted = position is None
    if inserted:
        position = sum(1 for entry in entries if instant(entry) <= instant(commit_info))
        entries.insert(position, commit_info)
        for other in series.values():
            for column in other.values():
                column.insert(position, None)
    columns = series[scope["id"]]
    # A measurement replaces the device's earlier one of the commit entirely.
    for column in columns.values():
        column.extend([None] * (len(entries) - len(column)))
        column[position] = None
    for name, case in snapshot["cases"].items():
        for kind, entry in case.items():
            for metric_name, value in series_values(entry).items():
                column = columns.setdefault(f"{name}.{kind}.{metric_name}", [])
                column.extend([None] * (len(entries) - len(column)))
                column[position] = value
    for other in series.values():
        for column in other.values():
            column.extend([None] * (len(entries) - len(column)))
    index["cases"] = {**index["cases"], **cases_meta}
    index["scopes"] = list(scopes.values())
    attach_tags(entries)
    store.write(
        {f"{PREFIX}snapshots/{snapshot['commit']}/{scope['id']}.json": snapshot},
        immutable=False,
    )
    # The index goes before the series. A series the page finds short reads as
    # unmeasured; one longer than the index would misalign every later commit.
    store.write({PREFIX + "index.json": index}, immutable=False)
    changed = series if inserted else {scope["id"]: columns}
    store.write(
        {f"{PREFIX}series/{key}.json": value for key, value in changed.items()},
        immutable=False,
    )


def cases_meta():
    return {
        name: {
            "title": preset["title"],
            "description": preset["description"],
            "workload": preset["config"]["workload"],
            "scene": preset["config"]["scene"],
            "implementation": preset["config"].get(
                "implementation", DEFAULT_IMPLEMENTATION
            ),
            "classic": bool(preset.get("classic")),
            "platforms": preset.get("platforms"),
        }
        for name, preset in TRACKED.items()
    }


def table(cases):
    """A readable summary of the medians, Compose beside classic."""
    columns = [
        ("cpu_ms_per_operation", "CPU/op ms"),
        ("cpu_ms_per_second", "CPU/s ms"),
        ("submission_p50_ms", "submit p50 ms"),
        ("completion_p50_ms", "done p50 ms"),
        ("close_p50_ms", "close p50 ms"),
        ("close_completion_p50_ms", "cleanup p50 ms"),
        ("ui_frame_max_ms", "UI max ms"),
        ("map_fps", "map FPS"),
        ("map_frame_p95_ms", "map p95 ms"),
        ("map_frame_max_ms", "map max ms"),
        ("map_late_percent", "map late %"),
        ("late_frames", "late frames"),
        ("startup_first_frame_ms", "startup ms"),
    ]
    lines = [f"{'case':<20}{'kind':<9}" + "".join(f"{h:>14}" for _, h in columns)]
    for name, case in cases.items():
        for kind, entry in case.items():
            cells = []
            for key, _ in columns:
                summary = entry["metrics"].get(key)
                cells.append(f"{summary['median']:>14.2f}" if summary else f"{'–':>14}")
            lines.append(f"{name:<20}{kind:<9}" + "".join(cells))
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=("android", "ios", "desktop", "web"))
    parser.add_argument(
        "--scope",
        required=True,
        help="Device id, such as pixel-8: lowercase letters, digits, dashes",
    )
    parser.add_argument(
        "--label", help="Device name shown on the page; defaults to the scope"
    )
    parser.add_argument(
        "--device",
        help="Android serial, iOS simulator UDID, or physical iPhone identifier",
    )
    parser.add_argument("--app", help="Packaged desktop executable")
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--output", type=Path, help="Where the runs go; must not exist")
    parser.add_argument(
        "--no-build", action="store_true", help="Reuse the apps built by a previous run"
    )
    target = parser.add_mutually_exclusive_group()
    target.add_argument("--directory", type=Path, help="Publish into this directory")
    target.add_argument(
        "--bucket", help="Publish into this R2 bucket, read through --url"
    )
    target.add_argument(
        "--local", action="store_true", help="Measure without publishing anywhere"
    )
    parser.add_argument("--url", help="The bucket's public URL, ending in a slash")
    parser.add_argument(
        "--dry-run", action="store_true", help="List uploads without making them"
    )
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", args.scope):
        parser.error("--scope must be lowercase letters, digits, and dashes")
    if args.platform in ("android", "ios") and not args.device:
        parser.error("--device is required")
    if args.platform == "desktop" and not args.app:
        parser.error("desktop requires --app PATH")
    if args.bucket and not args.url:
        parser.error("--bucket needs --url")
    if args.repeat < 1:
        parser.error("--repeat must be positive")

    head, dirty, on_main = checkout()
    # Only the bucket needs a clean commit on main; a directory holds local page data.
    gated = bool(args.bucket)
    stamp = datetime.datetime.now(datetime.timezone.utc)
    output = args.output or (
        ROOT
        / "build/benchmarks/publish"
        / f"{args.scope}-{head[:7]}-{stamp:%Y%m%dT%H%M%SZ}"
    )
    output.mkdir(parents=True, exist_ok=False)
    reason = publishable(head, dirty, on_main, {}) if gated else None
    if reason:
        print(f"Results will stay local: {reason}.", flush=True)

    cases = measure(
        args.platform,
        args.device,
        args.app,
        output,
        args.repeat,
        build=not args.no_build,
    )
    snapshot = {
        "commit": head,
        "scope": args.scope,
        "platform": args.platform,
        "device": args.label or args.scope,
        "measuredAt": stamp.isoformat(timespec="seconds"),
        "cases": cases,
    }
    (output / "snapshot.json").write_text(json.dumps(snapshot, indent=2) + "\n")
    print(table(cases))
    print(f"Runs and snapshot.json are in {output}")

    reason = publishable(head, dirty, on_main, cases) if gated else None
    if args.local or not (args.directory or args.bucket):
        return
    if reason:
        print(f"Not published: {reason}.")
        return
    store = (
        Bucket(args.bucket, args.url, args.dry_run)
        if args.bucket
        else Directory(args.directory)
    )
    scope = {
        "id": args.scope,
        "label": args.label or args.scope,
        "platform": args.platform,
    }
    commit_info = {
        "commit": head,
        "date": git("show", "-s", "--format=%cI", head),
        "title": git("show", "-s", "--format=%s", head),
        "tags": [],
    }
    sync(store, snapshot, scope, cases_meta(), commit_info)
    print(f"Published {head[:7]} for {args.scope}.")


if __name__ == "__main__":
    main()
