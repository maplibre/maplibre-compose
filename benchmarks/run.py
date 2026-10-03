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
CLASSIC_PACKAGE = "org.maplibre.compose.benchmark.classic"


def app_package(config):
    return (
        CLASSIC_PACKAGE
        if json.loads(config)["implementation"].startswith("classic-")
        else PACKAGE
    )


def validate_platform(platform, config):
    parsed = json.loads(config)
    if parsed.get("workload") == "runtime-startup" and platform == "web":
        raise ValueError("runtime-startup requires MapLibre Native")
    implementation = parsed["implementation"]
    for target in ("android", "ios"):
        if implementation == f"classic-{target}" and platform != target:
            raise ValueError(f"classic-{target} requires the {target} runner")


def android_apk(config):
    folder = "benchmarks" if app_package(config) == CLASSIC_PACKAGE else "demo-app"
    return f"{folder}/android/build/outputs/apk/release/android-release.apk"


def ios_app(config, simulator):
    classic = app_package(config) == CLASSIC_PACKAGE
    folder = "benchmarks/ios" if classic else "demo-app/ios"
    name = "ClassicBenchmark" if classic else "maplibre-compose-demo"
    sdk = "iphonesimulator" if simulator else "iphoneos"
    return f"{folder}/build/DerivedData/Build/Products/Release-{sdk}/{name}.app"


def ios_launch_args(device, config, simulator):
    package = app_package(config)
    if simulator:
        return [
            "xcrun",
            "simctl",
            "launch",
            "--terminate-running-process",
            "--console",
            device,
            package,
        ]
    return [
        "xcrun",
        "devicectl",
        "device",
        "process",
        "launch",
        "--device",
        device,
        "--terminate-existing",
        "--console",
        "--environment-variables",
        json.dumps({"MAP_BENCHMARK": config}),
        package,
    ]


def call(*command):
    return subprocess.check_output(command, text=True).strip()


def wait_for(path, process=None, poll=None):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if poll is not None:
            poll()
        logs = path.read_text(errors="replace")
        if "MAP_BENCHMARK ERROR" in logs or "FATAL EXCEPTION" in logs:
            raise RuntimeError(f"Benchmark failed; inspect {path}")
        if "MAP_BENCHMARK DONE" in logs:
            return
        if process is not None and process.poll() is not None:
            raise RuntimeError(
                f"App exited before completing the benchmark; inspect {path}"
            )
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
        app_package(config) + "/.MainActivity",
        "--es",
        "benchmark",
        shlex.quote(config),
    ]


def android(args, output):
    adb = args.adb
    package = app_package(args.config)
    call(*adb, "shell", "am", "force-stop", package)
    logger = None
    try:
        call(*android_launch_args(adb, args.config))
        pid = call(*adb, "shell", "pidof", package)
        with (output / "app.log").open("w") as log:
            logger = subprocess.Popen(
                [*adb, "logcat", "--pid=" + pid, "-v", "brief"], stdout=log, stderr=log
            )
            wait_for(output / "app.log")
    finally:
        if logger:
            stop(logger)
        call(*adb, "shell", "am", "force-stop", package)


def ios(args, output):
    with (output / "app.log").open("w") as log:
        app = subprocess.Popen(
            ios_launch_args(args.device, args.config, args.simulator),
            env=dict(os.environ, SIMCTL_CHILD_MAP_BENCHMARK=args.config),
            stdout=log,
            stderr=log,
        )
        try:
            wait_for(output / "app.log", app)
        finally:
            if args.simulator:
                subprocess.run(
                    [
                        "xcrun",
                        "simctl",
                        "terminate",
                        args.device,
                        app_package(args.config),
                    ],
                    capture_output=True,
                    check=False,
                )
            # devicectl --console forwards termination to the launched process.
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


def is_simulator(device):
    """Whether an iOS device identifier names a simulator rather than a physical iPhone."""
    devices = json.loads(call("xcrun", "simctl", "list", "devices", "--json"))[
        "devices"
    ]
    return device == "booted" or any(
        entry["udid"] == device for runtime in devices.values() for entry in runtime
    )


def setup(platform, config, device=None, app=None):
    """Install the app that runs [config] and return the launch context for [capture]."""
    context = {"device": device, "app": app}
    if platform == "android":
        adb = [
            str(Path(call(".mise/bin/android-sdk-root")) / "platform-tools/adb"),
            "-s",
            device,
        ]
        call(*adb, "install", "-r", android_apk(config))
        context["adb"] = adb
    elif platform == "ios":
        devices = json.loads(call("xcrun", "simctl", "list", "devices", "--json"))[
            "devices"
        ]
        simulator = device == "booted" or any(
            entry["udid"] == device for runtime in devices.values() for entry in runtime
        )
        app = app or ios_app(config, simulator)
        if simulator:
            call("xcrun", "simctl", "install", device, app)
        else:
            call(
                "xcrun",
                "devicectl",
                "device",
                "install",
                "app",
                "--device",
                device,
                app,
            )
        context["simulator"] = simulator
    return context


def capture(platform, context, config, output, repeat=1):
    """Run [config] [repeat] times into [output] and return the analyzed reports."""
    args = argparse.Namespace(config=config, **context)
    reports = []
    for index in range(repeat):
        directory = output if repeat == 1 else output / f"{index + 1:03d}"
        directory.mkdir(parents=True, exist_ok=True)
        {"android": android, "ios": ios, "desktop": desktop, "web": web}[platform](
            args, directory
        )
        reports.append(analyze(directory))
    return reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "platform", choices=("android", "ios", "desktop", "web", "analyze", "list")
    )
    parser.add_argument("--case", choices=sorted(CASES))
    parser.add_argument("--config", default="{}", help="JSON configuration overrides")
    parser.add_argument(
        "--implementation",
        choices=(
            "compose-imperative",
            "compose-declarative",
            "classic-android",
            "classic-ios",
        ),
    )
    parser.add_argument(
        "--device",
        help="Android serial, iOS simulator UDID, or physical iPhone identifier",
    )
    parser.add_argument(
        "--app", help="Packaged desktop executable or override iOS .app path"
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
        config = canonical_config(config)
        validate_platform(args.platform, config)
    except (ValueError, TypeError) as error:
        parser.error(str(error))
    args.output.mkdir(parents=True, exist_ok=False)
    context = setup(args.platform, config, args.device, args.app)
    for report in capture(args.platform, context, config, args.output, args.repeat):
        print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
