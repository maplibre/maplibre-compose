"""Measure first-parent commits and sync the metrics dashboard's data.

The data goes to a directory, or to the R2 bucket the published dashboard reads.
Each run measures the commits after the last one in the existing index. Earlier
commits keep the measurements they were published with, even after the reporter
changes; --rebuild measures every commit again.
"""

import argparse
import json
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

SCHEMA_VERSION = 4
REPORTER = Path("tools/code-metrics/build/install/code-metrics/bin/code-metrics")
# Summary fields the dashboard charts, by scope, next to each distribution's p90 and p99.
SERIES_FIELDS = [
    "loc",
    "testLoc",
    "functions",
    "packages",
    "packagesInCycles",
    "cognitiveComplexMethods",
    "cyclomaticComplexMethods",
    "longMethods",
]


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def encode(value):
    return json.dumps(value, separators=(",", ":")) + "\n"


class Directory:
    """A local copy of the data, as `mise run metrics:dev` serves it."""

    def __init__(self, root):
        self.root = root

    def read(self, key):
        path = self.root / key
        return json.loads(path.read_text()) if path.exists() else None

    def write(self, items, immutable):
        for key, value in items.items():
            path = self.root / key
            path.parent.mkdir(parents=True, exist_ok=True)
            path.with_suffix(".tmp").write_text(encode(value))
            path.with_suffix(".tmp").replace(path)


class Bucket:
    """The published data: read through its public URL and written with wrangler."""

    def __init__(self, name, url, dry_run):
        self.name, self.url, self.dry_run = name, url, dry_run

    def read(self, key):
        # A unique query string skips Cloudflare's cache, so a sync appends to the current index.
        # Cloudflare's bot protection rejects Python's default user agent.
        request = urllib.request.Request(
            f"{self.url}{key}?t={time.time_ns()}",
            headers={"User-Agent": "maplibre-compose-metrics"},
        )
        try:
            with urllib.request.urlopen(request) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            raise

    def write(self, items, immutable):
        if self.dry_run:
            for key, value in items.items():
                print(f"would upload {key} ({len(encode(value))} bytes)")
            return
        cache = (
            "public, max-age=31536000, immutable" if immutable else "public, max-age=60"
        )
        with tempfile.TemporaryDirectory() as temporary:

            def put(item):
                key, value = item
                path = Path(temporary) / key.replace("/", "_")
                path.write_text(encode(value))
                subprocess.run(
                    [
                        "wrangler",
                        "r2",
                        "object",
                        "put",
                        f"{self.name}/{key}",
                        "--remote",
                        f"--file={path}",
                        "--content-type=application/json",
                        f"--cache-control={cache}",
                    ],
                    check=True,
                    stdout=subprocess.DEVNULL,
                )

            with ThreadPoolExecutor(8) as pool:
                list(pool.map(put, items.items()))


def scope_id(scope):
    return scope["module"].replace("/", "_") if scope["module"] else scope["group"]


def is_ancestor(ancestor, descendant):
    command = ["git", "merge-base", "--is-ancestor", ancestor, descendant]
    return subprocess.run(command, check=False).returncode == 0


def first_parent(revisions):
    return git(
        "log", "--first-parent", "--reverse", "--format=%H", revisions
    ).splitlines()


def measure(commits, into):
    refs = into / "refs.txt"
    refs.write_text("\n".join(commits) + "\n")
    command = [str(REPORTER), "--scopes", "--refs-file", str(refs), "--out", str(into)]
    subprocess.run(command, check=True)


def sync(store, start, end, rebuild):
    index = None if rebuild else store.read("index.json")
    if index and index.get("schemaVersion") != SCHEMA_VERSION:
        raise SystemExit("The published index has another schema; sync with --rebuild.")
    if index:
        entries, scopes = index["commits"], {s["id"]: s for s in index["scopes"]}
        thresholds = index["thresholds"]
        series = {key: store.read(f"series/{key}.json") or {} for key in scopes}
        last = entries[-1]["commit"]
        head = git("rev-parse", end)
        if is_ancestor(head, last):
            commits = []  # Already indexed, as when a tag triggers the sync.
        elif is_ancestor(last, head):
            commits = first_parent(f"{last}..{head}")
        else:
            raise SystemExit(
                f"The last indexed commit {last} is not in {end}'s history."
            )
    else:
        entries, scopes, series, thresholds = [], {}, {}, None
        commits = first_parent(f"{start}~..{end}")

    titles = dict(
        line.split(" ", 1) for line in git("log", "--format=%H %s", end).splitlines()
    )
    with tempfile.TemporaryDirectory() as temporary:
        if commits:
            measure(commits, Path(temporary))
        # Uploads go out in batches, so a rebuild keeps every upload worker busy.
        pending = {}
        for commit in commits:
            snapshot = json.loads((Path(temporary) / f"{commit}.json").read_text())
            reports = {}
            for scope in snapshot["scopes"]:
                key = scope_id(scope)
                reports[key] = scope
                scopes[key] = {
                    "id": key,
                    "group": scope["group"],
                    "module": scope["module"],
                }
                values = {field: scope["summary"][field] for field in SERIES_FIELDS}
                for name, distribution in scope["distributions"].items():
                    for statistic in ["p90", "p99"]:
                        values[f"{name}.{statistic}"] = distribution[statistic]
                columns = series.setdefault(key, {})
                for name, value in values.items():
                    column = columns.setdefault(name, [])
                    column.extend([None] * (len(entries) - len(column)))
                    column.append(value)
            packed = {"commit": commit, "files": snapshot["files"], "scopes": reports}
            pending[f"snapshots/{commit}.json"] = packed
            if len(pending) == 32:
                store.write(pending, immutable=True)
                pending = {}
            entries.append(
                {
                    "commit": commit,
                    "date": snapshot["commitDate"],
                    "title": titles.get(commit, ""),
                }
            )
            thresholds = snapshot["thresholds"]
        store.write(pending, immutable=True)

    for columns in series.values():
        for column in columns.values():
            column.extend([None] * (len(entries) - len(column)))
    if commits:
        store.write(
            {f"series/{key}.json": columns for key, columns in series.items()},
            immutable=False,
        )
    # Tags can arrive after their commit was measured, so every sync refreshes them.
    tags = {}
    for line in git(
        "for-each-ref",
        "--format=%(refname:short) %(objectname) %(*objectname)",
        "refs/tags",
    ).splitlines():
        parts = line.split()
        tags.setdefault(parts[-1], []).append(parts[0])
    for entry in entries:
        entry["tags"] = tags.get(entry["commit"], [])
    # The index goes last, once everything it refers to exists.
    store.write(
        {
            "index.json": {
                "schemaVersion": SCHEMA_VERSION,
                "thresholds": thresholds,
                "commits": entries,
                "scopes": list(scopes.values()),
            }
        },
        immutable=False,
    )
    print(f"Measured {len(commits)} commits; the index has {len(entries)}.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    # The first release without the C++ JNI code; earlier commits measure a different architecture.
    parser.add_argument(
        "--from", dest="start", default="v0.14.0", help="First commit to measure"
    )
    parser.add_argument(
        "--to", dest="end", default="HEAD", help="Last commit to measure"
    )
    parser.add_argument(
        "--rebuild", action="store_true", help="Measure every commit again"
    )
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--output", type=Path, help="Sync into this directory")
    target.add_argument("--bucket", help="Sync into this R2 bucket, read through --url")
    parser.add_argument("--url", help="The bucket's public URL, ending in a slash")
    parser.add_argument(
        "--dry-run", action="store_true", help="List uploads without making them"
    )
    args = parser.parse_args()
    if args.bucket and not args.url:
        parser.error("--bucket needs --url")
    store = (
        Bucket(args.bucket, args.url, args.dry_run)
        if args.bucket
        else Directory(args.output)
    )
    sync(store, args.start, args.end, args.rebuild)


if __name__ == "__main__":
    main()
