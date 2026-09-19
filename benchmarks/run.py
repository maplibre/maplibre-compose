# /// script
# requires-python = ">=3.11"
# dependencies = ["opencv-python-headless==4.13.0.92", "numpy==2.4.3", "perfetto==0.58.2"]
# ///
"""Run one benchmark per fresh directory; retain recordings/traces for independent analysis."""

import argparse
import functools
import hashlib
import http.server
import json
import os
import platform
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from contextlib import contextmanager
from pathlib import Path

from analyze import analyze, configs_equal, logged_config, read_run, workload_metrics
from config import CASES, canonical_config, parse_config, workload
from performance import analyze_performance, process_cpu_metrics

ROOT = Path(__file__).resolve().parent
PACKAGE = "org.maplibre.compose.demoapp"
# Bounded readiness, full warm-up, measurement, and shutdown, including launch margin.
RUN_TIMEOUT = 120


def android_launch_args(adb, config):
    """The am start argv; config is shell-quoted because adb joins argv into a shell command."""
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


def call(*command, **kwargs):
    return subprocess.run(
        command, check=True, text=True, capture_output=True, **kwargs
    ).stdout.strip()


def wait_for(path, marker, timeout=RUN_TIMEOUT):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            logs = path.read_text(errors="replace")
            if "MAP_BENCHMARK ERROR" in logs or "FATAL EXCEPTION" in logs:
                raise RuntimeError(f"Benchmark failed; inspect {path}")
            if marker in logs:
                return
        time.sleep(0.1)
    raise TimeoutError(f"Missing {marker}; inspect {path}")


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def artifact_hash(path):
    path = Path(path)
    digest = hashlib.sha256()
    for file in sorted(path.rglob("*")) if path.is_dir() else [path]:
        if file.is_file():
            digest.update(
                str(file.relative_to(path) if path.is_dir() else file.name).encode()
            )
            with file.open("rb") as stream:
                digest.update(hashlib.file_digest(stream, "sha256").digest())
    return digest.hexdigest()


def desktop_artifact_hash(executable):
    executable = Path(executable).resolve()
    bundle = next(
        (parent for parent in executable.parents if parent.suffix == ".app"), None
    )
    return artifact_hash(bundle or executable)


def android(args, output, metadata):
    sdk = call(".mise/bin/android-sdk-root")
    adb = [str(Path(sdk) / "platform-tools/adb"), "-s", args.device]
    if args.trace and int(call(*adb, "shell", "getprop", "ro.build.version.sdk")) < 29:
        raise ValueError("Performance tracing requires Android API 29 or newer")
    scale = call(*adb, "shell", "settings", "get", "global", "animator_duration_scale")
    metadata["animator_duration_scale"] = float(scale) if scale != "null" else 1.0
    if metadata["animator_duration_scale"] != 1.0:
        raise ValueError("Android capture requires animator duration scale 1×")
    apk = args.app or "demo-app/android/build/outputs/apk/release/android-release.apk"
    digest = hashlib.sha256(Path(apk).read_bytes()).hexdigest()

    def installed_digest():
        paths = subprocess.run(
            [*adb, "shell", "pm", "path", PACKAGE],
            text=True,
            capture_output=True,
            check=False,
        ).stdout.splitlines()
        if not paths:
            return None
        installed = paths[0].removeprefix("package:")
        return call(*adb, "shell", "sha256sum", installed).split()[0]

    # Installing triggers an install-time compile that competes with the app for its whole
    # first minute; skip it when this artifact is already installed.
    if installed_digest() != digest:
        call(*adb, "install", "-r", apk)
        if installed_digest() != digest:
            raise ValueError("Installed APK does not match the requested artifact")
    metadata.update(
        apk_sha256=digest,
        fingerprint=call(*adb, "shell", "getprop", "ro.build.fingerprint"),
        display=call(*adb, "shell", "wm", "size"),
        density=call(*adb, "shell", "wm", "density"),
    )
    (output / "thermal-before.txt").write_text(
        call(*adb, "shell", "dumpsys", "thermalservice")
    )
    dimensions = re.findall(r"(\d+)x(\d+)", metadata["display"])[-1]
    metadata["uid"] = int(
        re.search(
            rf"(?m)^package:{re.escape(PACKAGE)} uid:(\d+)$",
            call(*adb, "shell", "cmd", "package", "list", "packages", "-U", PACKAGE),
        )[1]
    )
    remote = f"/data/local/tmp/map-benchmark-{os.getpid()}"
    remote_trace = f"/data/misc/perfetto-traces/map-benchmark-{os.getpid()}.trace"
    call(*adb, "shell", "am", "force-stop", PACKAGE)
    processes = []
    try:
        with (output / "capture.log").open("w") as log:
            if args.trace:
                trace = subprocess.Popen(
                    [*adb, "shell", "perfetto", "--txt", "-c", "-", "-o", remote_trace],
                    stdin=subprocess.PIPE,
                    stdout=log,
                    stderr=log,
                )
                processes.append(trace)
                trace.stdin.write((ROOT / "android.pbtxt").read_bytes())
                trace.stdin.close()
            if args.mode != "performance":
                recording_command = (
                    f"echo $$ > {remote}.pid; exec screenrecord --size {'x'.join(dimensions)} "
                    f"--time-limit {RUN_TIMEOUT} {remote}.mp4"
                )
                recorder = subprocess.Popen(
                    [*adb, "shell", "sh", "-c", shlex.quote(recording_command)],
                    stdout=log,
                    stderr=log,
                )
                processes.append(recorder)
            time.sleep(0.7)
            call(*android_launch_args(adb, args.config))
            pid = call(*adb, "shell", "pidof", PACKAGE)
            with (output / "app.log").open("w") as app_log:
                logger = subprocess.Popen(
                    [*adb, "logcat", "--pid=" + pid, "-v", "brief"],
                    stdout=app_log,
                    stderr=app_log,
                )
                processes.append(logger)
                wait_for(output / "app.log", "MAP_BENCHMARK MEASURE")
                if workload(args.config) == "input":
                    x, y = (str(int(v) // 2) for v in dimensions)
                    for _ in range(8):
                        time.sleep(parse_config(args.config)["durationMs"] / 9000)
                        call(*adb, "shell", "input", "tap", x, y)
                wait_for(output / "app.log", "MAP_BENCHMARK DONE", timeout=60)
                if args.trace:
                    trace.wait(timeout=RUN_TIMEOUT)
                    if trace.returncode:
                        raise RuntimeError("Perfetto failed; inspect capture.log")
                    call(
                        *adb, "pull", remote_trace, str(output / "trace.perfetto-trace")
                    )
                if args.mode != "performance":
                    time.sleep(0.5)
                    recording_pid = call(*adb, "shell", "cat", remote + ".pid")
                    if not recording_pid.isdigit():
                        raise ValueError("Invalid recorder PID")
                    call(*adb, "shell", "kill", "-2", recording_pid)
                    recorder.wait(timeout=RUN_TIMEOUT)
                    if recorder.returncode:
                        raise RuntimeError("Screenrecord failed; inspect capture.log")
                    call(*adb, "pull", remote + ".mp4", str(output / "screen.mp4"))
                    metadata["video"] = "screen.mp4"
    finally:
        for process in processes:
            stop(process)
        (output / "thermal-after.txt").write_text(
            call(*adb, "shell", "dumpsys", "thermalservice")
        )
        call(*adb, "shell", "am", "force-stop", PACKAGE)
        call(*adb, "shell", "rm", "-f", remote_trace, remote + ".mp4", remote + ".pid")


def ios(args, output, metadata):
    if workload(args.config) == "input":
        raise ValueError(
            "Automated iOS input injection and capture-clock calibration are not implemented"
        )
    command = ["xcrun", "simctl"]
    metadata["app_sha256"] = artifact_hash(
        call(*command, "get_app_container", args.device, PACKAGE, "app")
    )
    subprocess.run(
        [*command, "terminate", args.device, PACKAGE], capture_output=True, check=False
    )
    with (
        (output / "app.log").open("w") as log,
        (output / "capture.log").open("w") as capture_log,
    ):
        recorder = None
        if args.mode != "performance":
            recorder = subprocess.Popen(
                [
                    *command,
                    "io",
                    args.device,
                    "recordVideo",
                    "--codec=h264",
                    str(output / "screen.mp4"),
                ],
                stdout=capture_log,
                stderr=capture_log,
            )
        app = None
        try:
            time.sleep(0.7)
            app = subprocess.Popen(
                [*command, "launch", "--console", args.device, PACKAGE],
                env=dict(os.environ, SIMCTL_CHILD_MAP_BENCHMARK=args.config),
                stdout=log,
                stderr=log,
            )
            wait_for(output / "app.log", "MAP_BENCHMARK DONE")
            if recorder:
                recorder.send_signal(signal.SIGINT)
                recorder.wait(timeout=15)
                if recorder.returncode:
                    raise RuntimeError("Simulator capture failed")
        finally:
            if recorder:
                stop(recorder)
            subprocess.run(
                [*command, "terminate", args.device, PACKAGE],
                capture_output=True,
                check=False,
            )
            if app:
                stop(app)
    if args.mode != "performance":
        metadata["video"] = "screen.mp4"


def desktop(args, output, metadata):
    if workload(args.config) == "input":
        raise ValueError("Desktop adapter does not support the input scenario")
    executable = (
        args.app
        or "demo-app/desktop/build/compose/binaries/main/app/org.maplibre.compose.demoapp.app/Contents/MacOS/org.maplibre.compose.demoapp"
    )
    metadata["app_sha256"] = desktop_artifact_hash(executable)
    if args.mode != "performance":
        recorder_path = Path("build/benchmarks/record-window").resolve()
        recorder_path.parent.mkdir(parents=True, exist_ok=True)
        call(
            "xcrun",
            "swiftc",
            "-parse-as-library",
            "-swift-version",
            "5",
            str(ROOT / "record-window.swift"),
            "-o",
            str(recorder_path),
        )
    with (output / "app.log").open("w") as log:
        app = subprocess.Popen(
            [str(Path(executable).resolve())],
            env=dict(os.environ, MAP_BENCHMARK=args.config),
            stdout=log,
            stderr=log,
        )
        try:
            if args.mode == "performance":
                wait_for(output / "app.log", "MAP_BENCHMARK DONE")
            else:
                call(
                    str(recorder_path),
                    str(app.pid),
                    str(output / "screen.mp4"),
                    timeout=150,
                )
        finally:
            stop(app)
    if args.mode != "performance":
        metadata["video"] = "screen.mp4"


@contextmanager
def web_url(url, metadata):
    if url:
        metadata["assets_sha256"] = None
        yield url
        return
    with tempfile.TemporaryDirectory(prefix="map-benchmark-web-") as directory:
        for source in (
            "demo-app/common/build/processedResources/js/main",
            "demo-app/common/build/kotlin-webpack/js/developmentExecutable",
        ):
            shutil.copytree(source, directory, dirs_exist_ok=True)
        metadata["assets_sha256"] = artifact_hash(directory)
        handler = functools.partial(
            http.server.SimpleHTTPRequestHandler, directory=directory
        )
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}/"
        finally:
            server.shutdown()
            server.server_close()
            worker.join()


def web(args, output, metadata):
    if args.mode != "visual" or workload(args.config) == "input":
        raise ValueError("Web adapter supports non-input visual capture only")
    playwright = args.playwright or str(
        Path(call("mise", "where", "npm:playwright")) / "node_modules/playwright"
    )
    with web_url(args.url, metadata) as url:
        call(
            "node",
            str(ROOT / "browser.cjs"),
            playwright,
            str(output),
            args.config,
            url,
            timeout=150,
        )
    metadata["video"] = "screen.webm"


def validate_workload(output, reference=None):
    metadata, logs, density = read_run(output)
    if metadata["mode"] != "performance":
        analyze(output)
        return str(output)
    if reference is None:
        raise ValueError("Performance-only runs require --visual-reference")
    reference = Path(reference).resolve()
    other, other_logs, other_density = read_run(reference)
    if not other.get("video"):
        raise ValueError("Visual reference must be a capture with a recording")
    artifact = "apk_sha256" if metadata["platform"] == "android" else "app_sha256"
    keys = ("platform", "device", "host", artifact)
    if metadata["platform"] == "android":
        keys += ("fingerprint", "display", "density", "animator_duration_scale")
    if any(not metadata.get(k) or metadata[k] != other.get(k) for k in keys):
        raise ValueError(
            "Visual reference must match artifact, configuration, and device"
        )
    # The app logs the fully decoded parameters, so equivalent spellings compare equal there.
    logged, other_logged = logged_config(logs), logged_config(other_logs)
    if not configs_equal(logged, other_logged):
        raise ValueError(
            "Visual reference must match artifact, configuration, and device"
        )
    if density != other_density:
        raise ValueError("Visual reference must match scene density")
    analyze(reference)  # Revalidate raw pixels; a previous JSON report is insufficient.
    return str(reference)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "platform", choices=("android", "ios", "desktop", "web", "analyze", "list")
    )
    parser.add_argument("--config", default="{}", help="JSON configuration overrides")
    parser.add_argument("--case", choices=sorted(CASES))
    parser.add_argument(
        "--implementation", choices=("compose-imperative", "compose-declarative")
    )
    parser.add_argument(
        "--trace",
        action="store_true",
        help="Collect Android Perfetto in addition to process counters",
    )
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument(
        "--app", help="Android APK or desktop executable; iOS uses the installed demo"
    )
    parser.add_argument(
        "--mode", choices=("visual", "performance", "both"), default="visual"
    )
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--repeat",
        type=int,
        default=1,
        help="Run serial captures in numbered child directories",
    )
    parser.add_argument(
        "--visual-reference",
        type=Path,
        help="Validated capture of the same artifact, configuration, and device; required for performance-only runs",
    )
    parser.add_argument(
        "--url", help="External web server; by default serve the local demo build"
    )
    parser.add_argument("--playwright", help="Path to the installed Playwright module")
    args = parser.parse_args()
    if args.platform == "list":
        print(json.dumps(CASES, indent=2))
        return
    if args.output is None:
        parser.error("--output is required")
    args.trace = args.trace or args.mode == "both"
    if args.trace and args.platform not in {"android", "analyze"}:
        parser.error("Perfetto capture is Android-only")
    try:
        config = CASES.get(args.case, {}) | json.loads(args.config)
        if args.implementation:
            config["implementation"] = args.implementation
        args.config = canonical_config(config)
    except ValueError as error:
        parser.error(str(error))
    if (
        args.platform != "analyze"
        and args.mode == "performance"
        and args.visual_reference is None
    ):
        parser.error("--mode performance requires --visual-reference")
    if args.repeat < 1 or (args.platform == "analyze" and args.repeat != 1):
        parser.error("--repeat must be positive and is only supported for new captures")
    output = args.output.resolve()
    if args.repeat > 1:
        output.mkdir(parents=True, exist_ok=False)
        for index in range(args.repeat):
            command = [
                sys.executable,
                str(Path(__file__).resolve()),
                args.platform,
                "--config",
                args.config,
                "--device",
                args.device,
                "--mode",
                args.mode,
                "--output",
                str(output / f"{index + 1:03d}"),
            ]
            if args.trace:
                command.append("--trace")
            for option in ("app", "visual_reference", "url", "playwright"):
                value = getattr(args, option)
                if value is not None:
                    command.extend(["--" + option.replace("_", "-"), str(value)])
            subprocess.run(command, check=True)
        return
    if args.platform != "analyze":
        output.mkdir(parents=True, exist_ok=False)
        metadata = {
            "schema": 4,
            "platform": args.platform,
            "config": args.config,
            "case": args.case,
            "trace": args.trace,
            "mode": args.mode,
            "device": args.device,
            "host": platform.node(),
            "commit": call("git", "rev-parse", "HEAD"),
            "diff_sha256": hashlib.sha256(
                subprocess.check_output(["git", "diff", "HEAD"])
            ).hexdigest(),
        }
        try:
            {"android": android, "ios": ios, "desktop": desktop, "web": web}[
                args.platform
            ](args, output, metadata)
        finally:
            (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    metadata = json.loads((output / "metadata.json").read_text())
    reference = args.visual_reference or metadata.get("visual_reference")
    validated = validate_workload(output, reference)
    if metadata["mode"] == "performance":
        metadata["visual_reference"] = validated
        (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    if (output / "trace.perfetto-trace").exists():
        cpu = analyze_performance(output)
    else:
        _, logs, _ = read_run(output)
        cpu = process_cpu_metrics(logs)
    if cpu is not None:
        _, logs, _ = read_run(output)
        cpu["workload"] = workload_metrics(logs)
        cpu["scene"] = json.loads(
            re.search(r"MAP_BENCHMARK SCENE (\{[^\n]+\})", logs)[1]
        )
        cpu["visual_reference"] = validated
        # allow_nan=False turns any future unvalidated non-finite metric into a loud failure.
        (output / "performance.json").write_text(
            json.dumps(cpu, indent=2, allow_nan=False) + "\n"
        )
        print(json.dumps(cpu, indent=2))
    else:
        print(
            "warning: no CPU counter or frame timing was collected; no performance.json written",
            file=sys.stderr,
        )
    if metadata["mode"] != "performance":
        print((output / "visual.json").read_text())


if __name__ == "__main__":
    main()
