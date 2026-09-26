"""Build local dashboard history and scoped reports. No uploads."""

import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, separators=(",", ":")) + "\n")
    temporary.replace(path)


def scope_id(scope):
    return scope["module"].replace("/", "_") if scope["module"] else scope["group"]


def build_history(since, output, step):
    install = Path("tools/code-metrics/build/install/code-metrics")
    # Cache the installed analyzer and dependency versions, including local changes.
    digest = hashlib.sha256()
    for jar in sorted((install / "lib").glob("*.jar")):
        digest.update(jar.name.encode())
        if jar.name.startswith("code-metrics-"):
            digest.update(jar.read_bytes())
    version = digest.hexdigest()[:16]
    cache = Path("build/metrics/history") / version
    cache.mkdir(parents=True, exist_ok=True)
    commits = git(
        "log",
        "--first-parent",
        "--reverse",
        f"--since-as-filter={since}",
        "--format=%H",
        "HEAD",
    ).splitlines()
    if not commits:
        raise ValueError(f"No commits since {since}")
    selected = commits[::step]
    if selected[-1] != commits[-1]:
        selected.append(commits[-1])
    missing = [commit for commit in selected if not (cache / f"{commit}.json").exists()]
    if missing:
        refs = cache / "refs.txt"
        refs.write_text("\n".join(missing) + "\n")
        subprocess.run(
            [
                str(install / "bin/code-metrics"),
                "--scopes",
                "--refs-file",
                str(refs),
                "--out",
                str(cache),
            ],
            check=True,
        )
    tags = {}
    for line in git(
        "for-each-ref",
        "--format=%(refname:short) %(objectname) %(*objectname)",
        "refs/tags",
    ).splitlines():
        parts = line.split()
        tags.setdefault(parts[-1], []).append(parts[0])
    # Detekt's defaults for its CognitiveComplexMethod, CyclomaticComplexMethod and LongMethod rules.
    thresholds = {
        "functionCognitiveComplexity": 15,
        "functionCyclomaticComplexity": 14,
        "functionLines": 60,
    }
    summary_keys = ["loc", "testLoc", "functions", "packages", "packagesInCycles"]
    # Replace earlier exports rather than leave files no index refers to.
    shutil.rmtree(output, ignore_errors=True)
    entries, scopes, series = [], {}, {}
    for index, commit in enumerate(selected):
        snapshot = json.loads((cache / f"{commit}.json").read_text())
        for scope in snapshot["scopes"]:
            key = scope_id(scope)
            scopes[key] = {
                "id": key,
                "group": scope["group"],
                "module": scope["module"],
            }
            values = {k: scope["summary"][k] for k in summary_keys}
            for name, distribution in scope["distributions"].items():
                for statistic in ["p90", "p99"]:
                    values[f"{name}.{statistic}"] = distribution[statistic]
                if name in thresholds:
                    values[f"{name}.over"] = sum(
                        count
                        for value, count in distribution["histogram"].items()
                        if int(value) > thresholds[name]
                    )
            columns = series.setdefault(key, {})
            for name, value in values.items():
                columns.setdefault(name, [None] * len(selected))[index] = value
            write_json(
                output / "snapshots" / commit / f"{key}.json",
                {"commit": commit, **scope},
            )
        entries.append(
            {
                "commit": commit,
                "date": snapshot["commitDate"],
                "title": git("show", "-s", "--format=%s", commit),
                "tags": tags.get(commit, []),
            }
        )
    for key, columns in series.items():
        write_json(output / "series" / f"{key}.json", columns)
    # Publish the index last; all its referenced objects now exist.
    write_json(
        output / "index.json",
        {
            "schemaVersion": 3,
            "step": step,
            "totalCommits": len(commits),
            "thresholds": thresholds,
            "commits": entries,
            "scopes": list(scopes.values()),
        },
    )
    print(f"Wrote {len(entries)} commits × {len(scopes)} scopes to {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--since", default="2 months ago")
    parser.add_argument("--output", type=Path, default=Path("docs/public/metrics-data"))
    parser.add_argument("--step", type=int, default=1, help="Sample every Nth commit")
    args = parser.parse_args()
    if args.step < 1:
        parser.error("--step must be positive")
    build_history(args.since, args.output, args.step)


if __name__ == "__main__":
    main()
