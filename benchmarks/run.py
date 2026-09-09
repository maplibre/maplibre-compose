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
import re
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from pathlib import Path

from analyze import analyze, read_run
from performance import analyze_performance, process_cpu_metrics

ROOT = Path(__file__).resolve().parent
PACKAGE = "org.maplibre.compose.demoapp"


def call(*command, **kwargs):
    return subprocess.run(
        command, check=True, text=True, capture_output=True, **kwargs
    ).stdout.strip()


def wait_for(path, marker, timeout=30):
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


def android(args, output, metadata):
    sdk = call(".mise/bin/android-sdk-root")
    adb = [str(Path(sdk) / "platform-tools/adb"), "-s", args.device]
    if (
        args.mode != "visual"
        and int(call(*adb, "shell", "getprop", "ro.build.version.sdk")) < 29
    ):
        raise ValueError("Performance tracing requires Android API 29 or newer")
    apk = args.app or "demo-app/android/build/outputs/apk/release/android-release.apk"
    call(*adb, "install", "-r", apk)
    installed = (
        call(*adb, "shell", "pm", "path", PACKAGE)
        .splitlines()[0]
        .removeprefix("package:")
    )
    digest = hashlib.sha256(Path(apk).read_bytes()).hexdigest()
    if call(*adb, "shell", "sha256sum", installed).split()[0] != digest:
        raise ValueError("Installed APK does not match the requested artifact")
    metadata.update(
        apk_sha256=digest,
        fingerprint=call(*adb, "shell", "getprop", "ro.build.fingerprint"),
        display=call(*adb, "shell", "wm", "size"),
        density=call(*adb, "shell", "wm", "density"),
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
            if args.mode != "visual":
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
                recorder = subprocess.Popen(
                    [
                        *adb,
                        "shell",
                        "screenrecord",
                        "--size",
                        "x".join(dimensions),
                        "--time-limit",
                        "24",
                        remote + ".mp4",
                    ],
                    stdout=log,
                    stderr=log,
                )
                processes.append(recorder)
            time.sleep(0.7)
            call(
                *adb,
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                PACKAGE + "/.MainActivity",
                "--es",
                "benchmark",
                args.config,
            )
            pid = call(*adb, "shell", "pidof", PACKAGE)
            with (output / "app.log").open("w") as app_log:
                logger = subprocess.Popen(
                    [*adb, "logcat", "--pid=" + pid, "-v", "brief"],
                    stdout=app_log,
                    stderr=app_log,
                )
                processes.append(logger)
                wait_for(output / "app.log", "MAP_BENCHMARK MEASURE")
                if args.config.startswith("input,"):
                    x, y = (str(int(v) // 2) for v in dimensions)
                    for _ in range(8):
                        time.sleep(1)
                        call(*adb, "shell", "input", "tap", x, y)
                wait_for(output / "app.log", "MAP_BENCHMARK DONE", timeout=20)
                if args.mode != "visual":
                    trace.wait(timeout=30)
                    if trace.returncode:
                        raise RuntimeError("Perfetto failed; inspect capture.log")
                    call(
                        *adb, "pull", remote_trace, str(output / "trace.perfetto-trace")
                    )
                if args.mode != "performance":
                    recorder.wait(timeout=30)
                    if recorder.returncode:
                        raise RuntimeError("Screenrecord failed; inspect capture.log")
                    call(*adb, "pull", remote + ".mp4", str(output / "screen.mp4"))
                    metadata["video"] = "screen.mp4"
    finally:
        for process in processes:
            stop(process)
        call(*adb, "shell", "am", "force-stop", PACKAGE)
        call(*adb, "shell", "rm", "-f", remote_trace, remote + ".mp4")


def ios(args, output, metadata):
    if args.config.startswith("input,"):
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
    if args.config.startswith("input,"):
        raise ValueError("Desktop adapter supports animation/setter workloads only")
    executable = (
        args.app
        or "demo-app/desktop/build/compose/binaries/main/app/org.maplibre.compose.demoapp.app/Contents/MacOS/org.maplibre.compose.demoapp"
    )
    metadata["app_sha256"] = artifact_hash(Path(executable).resolve().parents[1])
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
                    timeout=40,
                )
        finally:
            stop(app)
    if args.mode != "performance":
        metadata["video"] = "screen.mp4"


def web(args, output, metadata):
    if args.mode != "visual" or args.config.startswith("input,"):
        raise ValueError("Web adapter supports animation/setter visual capture only")
    playwright = args.playwright or str(
        Path(call("mise", "where", "npm:playwright")) / "node_modules/playwright"
    )
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
            url = args.url or f"http://127.0.0.1:{server.server_port}/"
            if args.url:
                metadata["assets_sha256"] = (
                    None  # A remote server's assets were not verified.
                )
            call(
                "node",
                str(ROOT / "browser.cjs"),
                playwright,
                str(output),
                args.config,
                url,
                timeout=45,
            )
        finally:
            server.shutdown()
            server.server_close()
            worker.join()
    metadata["video"] = "screen.webm"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "platform", choices=("android", "ios", "desktop", "web", "analyze")
    )
    parser.add_argument("--config", default="animation,surface,default,0")
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument(
        "--app", help="Android APK or desktop executable; iOS uses the installed demo"
    )
    parser.add_argument(
        "--mode", choices=("visual", "performance", "both"), default="visual"
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--url", help="External web server; by default serve the local demo build"
    )
    parser.add_argument("--playwright", help="Path to the installed Playwright module")
    args = parser.parse_args()
    if not re.fullmatch(
        r"(animation|setters|input),(surface|texture),(default|[1-9][0-9]{0,2}),(0|[1-9][0-9]{0,4})",
        args.config,
    ):
        parser.error("Expected scenario,surface,maximumFps,load")
    _, _, fps, load = args.config.split(",")
    if (fps != "default" and int(fps) > 240) or int(load) > 10000:
        parser.error("Maximum FPS is 240 and maximum load is 10000")
    output = args.output.resolve()
    if args.platform != "analyze":
        output.mkdir(parents=True, exist_ok=False)
        metadata = {
            "schema": 1,
            "platform": args.platform,
            "config": args.config,
            "mode": args.mode,
            "device": args.device,
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
    if (output / "trace.perfetto-trace").exists():
        print(json.dumps(analyze_performance(output), indent=2))
    else:
        _, logs, _ = read_run(output)
        cpu = process_cpu_metrics(logs)
        if cpu is not None:
            (output / "performance.json").write_text(json.dumps(cpu, indent=2) + "\n")
            print(json.dumps(cpu, indent=2))
    if list(output.glob("screen.*")):
        print(json.dumps(analyze(output), indent=2))


if __name__ == "__main__":
    main()
