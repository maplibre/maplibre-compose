"""Run a workload, save its logs and measurements, and optionally repeat it."""

import argparse
import functools
import http.server
import json
import os
import shlex
import shutil
import subprocess
import tempfile
import threading
import time
from pathlib import Path

from config import CASES, canonical_config
from performance import analyze

ROOT = Path(__file__).resolve().parent
PACKAGE = "org.maplibre.compose.demoapp"


def call(*command):
    return subprocess.check_output(command, text=True).strip()


def wait_for(path):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        logs = path.read_text(errors="replace")
        if "MAP_BENCHMARK ERROR" in logs or "FATAL EXCEPTION" in logs:
            raise RuntimeError(f"Benchmark failed; inspect {path}")
        if "MAP_BENCHMARK DONE" in logs:
            return
        time.sleep(0.1)
    raise TimeoutError(f"Benchmark did not finish; inspect {path}")


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def android_launch_args(adb, config):
    # adb joins argv into a remote shell command, so JSON needs shell quoting.
    return [
        *adb,
        "shell",
        "am",
        "start",
        "-W",
        "--activity-clear-task",
        "-n",
        PACKAGE + "/.MainActivity",
        "--es",
        "benchmark",
        shlex.quote(config),
    ]


def android(args, output):
    adb = args.adb
    call(*adb, "shell", "am", "force-stop", PACKAGE)
    logger = None
    try:
        call(*android_launch_args(adb, args.config))
        pid = call(*adb, "shell", "pidof", PACKAGE)
        with (output / "app.log").open("w") as log:
            logger = subprocess.Popen(
                [*adb, "logcat", "--pid=" + pid, "-v", "brief"], stdout=log, stderr=log
            )
            wait_for(output / "app.log")
    finally:
        if logger:
            stop(logger)
        call(*adb, "shell", "am", "force-stop", PACKAGE)


def ios(args, output):
    command = ["xcrun", "simctl"]
    subprocess.run(
        [*command, "terminate", args.device, PACKAGE], capture_output=True, check=False
    )
    with (output / "app.log").open("w") as log:
        app = subprocess.Popen(
            [*command, "launch", "--console", args.device, PACKAGE],
            env=dict(os.environ, SIMCTL_CHILD_MAP_BENCHMARK=args.config),
            stdout=log,
            stderr=log,
        )
        try:
            wait_for(output / "app.log")
        finally:
            subprocess.run(
                [*command, "terminate", args.device, PACKAGE],
                capture_output=True,
                check=False,
            )
            stop(app)


def desktop(args, output):
    executable = Path(args.app).resolve()
    with (output / "app.log").open("w") as log:
        app = subprocess.Popen(
            [str(executable)],
            env=dict(os.environ, MAP_BENCHMARK=args.config),
            stdout=log,
            stderr=log,
        )
        try:
            wait_for(output / "app.log")
        finally:
            stop(app)


def web(args, output):
    call("mise", "run", "deps:chromium")
    playwright = str(
        Path(call("mise", "where", "npm:playwright")) / "node_modules/playwright"
    )
    with tempfile.TemporaryDirectory(prefix="map-benchmark-web-") as directory:
        for source in (
            "demo-app/common/build/processedResources/js/main",
            "demo-app/common/build/kotlin-webpack/js/developmentExecutable",
        ):
            shutil.copytree(source, directory, dirs_exist_ok=True)
        handler = functools.partial(
            http.server.SimpleHTTPRequestHandler, directory=directory
        )
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            subprocess.run(
                [
                    "node",
                    str(ROOT / "browser.cjs"),
                    playwright,
                    str(output),
                    args.config,
                    f"http://127.0.0.1:{server.server_port}/",
                ],
                check=True,
                timeout=150,
            )
        finally:
            server.shutdown()
            server.server_close()
            worker.join()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "platform", choices=("android", "ios", "desktop", "web", "analyze", "list")
    )
    parser.add_argument("--case", choices=sorted(CASES))
    parser.add_argument("--config", default="{}", help="JSON configuration overrides")
    parser.add_argument(
        "--implementation",
        choices=("compose-imperative", "compose-declarative", "classic-android"),
    )
    parser.add_argument("--device", help="Android serial or iOS simulator UDID")
    parser.add_argument(
        "--app", help="Packaged desktop executable (required for desktop)"
    )
    parser.add_argument("--output", type=Path)
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args()
    if args.platform == "list":
        print(json.dumps(CASES, indent=2))
        return
    if args.output is None:
        parser.error("--output is required")
    if args.platform == "analyze":
        print(json.dumps(analyze(args.output), indent=2))
        return
    if args.platform in ("android", "ios") and not args.device:
        parser.error("--device is required")
    if args.platform == "desktop" and not args.app:
        parser.error("desktop requires --app PATH")
    if args.repeat < 1:
        parser.error("--repeat must be positive")
    try:
        config = CASES.get(args.case, {}) | json.loads(args.config)
        if args.implementation:
            config["implementation"] = args.implementation
        args.config = canonical_config(config)
        if (
            config.get("implementation") == "classic-android"
            and args.platform != "android"
        ):
            parser.error("classic-android requires the android runner")
    except (ValueError, TypeError) as error:
        parser.error(str(error))
    args.output.mkdir(parents=True, exist_ok=False)
    if args.platform == "android":
        args.adb = [
            str(Path(call(".mise/bin/android-sdk-root")) / "platform-tools/adb"),
            "-s",
            args.device,
        ]
        call(
            *args.adb,
            "install",
            "-r",
            "demo-app/android/build/outputs/apk/release/android-release.apk",
        )
    for index in range(args.repeat):
        output = args.output if args.repeat == 1 else args.output / f"{index + 1:03d}"
        output.mkdir(exist_ok=True)
        {"android": android, "ios": ios, "desktop": desktop, "web": web}[args.platform](
            args, output
        )
        print(json.dumps(analyze(output), indent=2))


if __name__ == "__main__":
    main()
