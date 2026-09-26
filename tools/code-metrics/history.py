"""Build local dashboard history and scoped reports. No uploads."""

import argparse
import hashlib
import json
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
    module = (scope["module"] or "all").replace("/", "_")
    return f"{scope['group']}--{module}--{scope['sourceSet'] or 'all'}"


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
    entries, scopes, series = [], {}, {}
    for commit in selected:
        snapshot = json.loads((cache / f"{commit}.json").read_text())
        prefix = f"snapshots/{version}/{commit}/"
        for scope in snapshot["scopes"]:
            # Keep the history series small while retaining each distribution's center and tail.
            for name, distribution in scope["distributions"].items():
                stem = name.removesuffix("Complexity")
                for percentile in ["p50", "p75", "p90", "p99", "max"]:
                    scope["summary"][stem + percentile[0].upper() + percentile[1:]] = (
                        distribution[percentile]
                    )
            key = scope_id(scope)
            scopes[key] = {k: scope[k] for k in ["group", "module", "sourceSet"]}
            scopes[key].update(id=key, path=f"series/{version}/{key}.json")
            series.setdefault(key, []).append(
                {"commit": commit, "summary": scope["summary"]}
            )
            write_json(output / prefix / f"{key}.json", {"commit": commit, **scope})
        entries.append(
            {
                "commit": commit,
                "commitDate": snapshot["commitDate"],
                "title": git("show", "-s", "--format=%s", commit),
                "tags": tags.get(commit, []),
                "path": prefix,
            }
        )
    for key, points in series.items():
        write_json(output / scopes[key]["path"], points)
    # Publish the index last; all its referenced objects now exist.
    write_json(
        output / "index.json",
        {
            "schemaVersion": 2,
            "reporterVersion": version,
            "since": since,
            "step": step,
            "totalCommits": len(commits),
            "snapshots": entries,
            "scopes": list(scopes.values()),
        },
    )
    print(f"Wrote {len(entries)} snapshots × up to {len(scopes)} scopes to {output}")


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
