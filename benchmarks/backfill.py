"""Measure released versions with this checkout's Android benchmark harness.

prepare: check out a release tag under build/benchmarks/backfill/<tag> and overlay the
  harness, keeping the release's library sources and dependency versions.
build: assemble the Compose and classic APKs there. Harness code that does not compile
  against the release's API is ported by hand in that checkout first.
archive: write a manifest for each built release and, with --upload, replace the artifact
  archive in the bucket.
run: install archived (or, with --local, just-built) APKs on one Android device and
  measure each release's plan. Finished runs are reused when a run is restarted.
publish: merge one device's finished results into the benchmarks page data.
"""

import argparse
import datetime
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import publish
import run as runner
from performance import analyze

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build/benchmarks/backfill"
INDEX = "benchmarks/artifacts/index.json"
APPS = {
    "compose": ("demo-app", runner.PACKAGE),
    "classic": ("benchmarks", runner.CLASSIC_PACKAGE),
}
HARNESS = "demo-app/common/src/{}/kotlin/org/maplibre/compose/demoapp/benchmark"
# The demo source sets an Android build compiles.
ANDROID_SOURCE_SETS = (
    "commonMain",
    "maplibreNativeMain",
    "androidJvmMain",
    "androidMain",
)


def git(*args, cwd=ROOT):
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def prepare(tag, classic):
    checkout = WORK / tag
    if checkout.exists():
        raise SystemExit(f"{checkout} exists; delete it to prepare again")
    if git("status", "--porcelain", "--", "benchmarks", "demo-app"):
        raise SystemExit("Commit the harness first; the manifests name its commit")
    release = git("rev-parse", f"{tag}^{{commit}}")
    subprocess.run(
        [
            "git",
            "clone",
            "--quiet",
            "--shared",
            "--no-checkout",
            str(ROOT),
            str(checkout),
        ],
        check=True,
    )
    git("checkout", "--quiet", "--detach", release, cwd=checkout)
    for name in ("benchmarks/core", "benchmarks/android"):
        shutil.rmtree(checkout / name, ignore_errors=True)
        shutil.copytree(
            ROOT / name, checkout / name, ignore=shutil.ignore_patterns("build")
        )
    shutil.copytree(
        ROOT / "benchmarks/build/fixtures", checkout / "benchmarks/build/fixtures"
    )
    if (ROOT / "local.properties").exists():
        shutil.copy(ROOT / "local.properties", checkout / "local.properties")

    # The classic SDK is a benchmark-only dependency; its Maven coordinates come from
    # this checkout, since they have changed across SDK releases.
    catalog = (checkout / "gradle/libs.versions.toml").read_text()
    catalog = re.sub(
        r"^maplibre-android(OpenGl|Vulkan)? = .*\n", "", catalog, flags=re.MULTILINE
    )
    current = (ROOT / "gradle/libs.versions.toml").read_text()
    libraries = "".join(
        re.findall(r"^maplibre-android(?:OpenGl|Vulkan) = .*\n", current, re.MULTILINE)
    )
    catalog = catalog.replace(
        "[versions]\n", f'[versions]\nmaplibre-android = "{classic}"\n', 1
    ).replace("[libraries]\n", "[libraries]\n" + libraries, 1)
    write(checkout / "gradle/libs.versions.toml", catalog)
    versions = dict(re.findall(r'^([\w-]+) = "([^"]+)"$', catalog, re.MULTILINE))

    settings = checkout / "settings.gradle.kts"
    modules = [
        ":",
        ":lib",
        ":lib:location",
        ":lib:maplibre-compose",
        ":lib:maplibre-compose-runtime-opengl-android",
        ":benchmarks:core",
        ":benchmarks:android",
        ":demo-app:android",
    ]
    included = ", ".join(f'"{module}"' for module in modules)
    write(
        settings,
        settings.read_text().split("\ninclude(")[0] + f"\ninclude({included})\n",
    )
    write(checkout / "build.gradle.kts", 'plugins { id("module-conventions") }\n')

    fixtures = hashlib.sha256()
    for name in ("benchmarks/fixtures/manifest.json", "benchmarks/prepare_fixtures.py"):
        fixtures.update((ROOT / name).read_bytes())
    build = {
        "commit": release,
        # The harness is not the release's, so the build is dirty by construction.
        "dirty": True,
        "fixtures": fixtures.hexdigest()[:12],
        "dependency_versions": {
            "classic_android": classic,
            "native_ffi": versions["maplibre-nativeFfi"],
            "compose": versions["gradle-compose"],
            "kotlin": versions["gradle-kotlin"],
        },
        "backfill": {
            "release": tag,
            "harnessCommit": git("rev-parse", "HEAD"),
            "libraryTree": git("rev-parse", f"{tag}:lib"),
        },
    }
    # The generated build info needs this checkout's buildSrc; releases get a constant.
    core = checkout / "benchmarks/core"
    shutil.rmtree(core / "src/commonTest")
    write(
        core
        / "src/commonMain/kotlin/org/maplibre/compose/benchmark/BenchmarkBuildInfo.kt",
        "package org.maplibre.compose.benchmark\n\n"
        f"internal const val BenchmarkBuildInfo = {json.dumps(json.dumps(build))}\n",
    )
    write(
        core / "build.gradle.kts",
        """plugins {
  id("module-conventions")
  id("android-library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.android.library.get().pluginId)
  id(libs.plugins.kotlin.serialization.get().pluginId)
}

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  android { namespace = "org.maplibre.compose.benchmark" }
  sourceSets.commonMain.dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
  }
}
""",
    )
    host(checkout)
    record_output(checkout)
    write(WORK / f"{tag}.json", json.dumps(build, indent=2) + "\n")
    print(
        f"Prepared {checkout}. Build it, and port harness code that does not compile."
    )


def host(checkout):
    """Replace the release's demo app with an app that only runs the Compose harness."""
    app = checkout / "demo-app/android"
    shutil.rmtree(app)
    gradle = (ROOT / "demo-app/android/build.gradle.kts").read_text()
    gradle = re.sub(r"^.*[sS]creenshot.*\n", "", gradle, flags=re.MULTILINE)
    gradle = gradle.replace(
        '  implementation(project(":demo-app:common"))\n',
        """  implementation(project(":lib:maplibre-compose"))
  implementation(project(":benchmarks:core"))
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.jetbrains.compose.material3)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.io.core)
""",
    ).replace(
        "  packaging {",
        '  sourceSets["main"].assets.srcDir("../../benchmarks/build/fixtures")\n  packaging {',
    )
    write(app / "build.gradle.kts", gradle)
    write(
        app / "src/main/AndroidManifest.xml",
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.INTERNET" />
  <application android:label="Release benchmarks" android:theme="@android:style/Theme.Material.Light.NoActionBar">
    <profileable android:shell="true" />
    <activity android:name=".MainActivity" android:exported="true" />
  </application>
</manifest>
""",
    )
    kotlin = app / "src/main/kotlin/org/maplibre/compose/demoapp"
    write(
        kotlin / "MainActivity.kt",
        """package org.maplibre.compose.demoapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.maplibre.compose.benchmark.BenchmarkConfig
import org.maplibre.compose.demoapp.benchmark.BenchmarkRun
import org.maplibre.compose.demoapp.generated.Res

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    Res.context = applicationContext
    val config = checkNotNull(BenchmarkConfig.parse(intent.getStringExtra("benchmark")))
    setContent { BenchmarkRun(config) }
  }
}
""",
    )
    # The harness reads fixtures through the demo's generated resources; serve them from assets.
    write(
        kotlin / "generated/Res.kt",
        """package org.maplibre.compose.demoapp.generated

import android.content.Context

internal object Res {
  lateinit var context: Context

  fun readBytes(path: String): ByteArray =
    context.assets.open(path.removePrefix("files/")).use { it.readBytes() }

  fun getUri(path: String): String = "asset://" + path.removePrefix("files/")
}
""",
    )
    # One Android source set: expect declarations and their actuals become plain declarations.
    for source_set in ANDROID_SOURCE_SETS:
        folder = ROOT / HARNESS.format(source_set)
        for path in sorted(folder.glob("*.kt")) if folder.exists() else []:
            text = re.sub(
                r"(?:@Composable\s+)?internal expect [^\n]*\n", "", path.read_text()
            )
            write(
                kotlin / "benchmark" / path.name,
                text.replace("internal actual ", "internal "),
            )


def record_output(checkout):
    """Tee the benchmark log to a file; logd can drop lines in the end-of-run burst."""
    write(
        checkout
        / "benchmarks/core/src/androidMain/kotlin/org/maplibre/compose/benchmark/BenchmarkLog.kt",
        """package org.maplibre.compose.benchmark

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

fun recordBenchmarkOutput(context: Context) {
  val console = System.out
  val file = FileOutputStream(File(context.getExternalFilesDir(null), "benchmark.log"))
  val tee =
    object : OutputStream() {
      override fun write(value: Int) {
        file.write(value)
        console.write(value)
      }

      override fun write(bytes: ByteArray, offset: Int, length: Int) {
        file.write(bytes, offset, length)
        console.write(bytes, offset, length)
      }

      override fun flush() {
        file.flush()
        console.flush()
      }
    }
  System.setOut(PrintStream(tee, true))
}
""",
    )
    for name in (
        "demo-app/android/src/main/kotlin/org/maplibre/compose/demoapp/MainActivity.kt",
        "benchmarks/android/src/main/kotlin/org/maplibre/compose/benchmark/classic/MainActivity.kt",
    ):
        path = checkout / name
        text = path.read_text()
        call = "super.onCreate(savedInstanceState)"
        assert text.count(call) == 1, name
        path.write_text(
            text.replace(
                call,
                call
                + "\n    org.maplibre.compose.benchmark.recordBenchmarkOutput(this)",
            )
        )


def build(tag):
    for folder, _ in APPS.values():
        subprocess.run(
            ["./gradlew", f":{folder}:android:assembleRelease"],
            cwd=WORK / tag,
            check=True,
        )


def apk(tag, kind):
    return (
        WORK
        / tag
        / APPS[kind][0]
        / "android/build/outputs/apk/release/android-release.apk"
    )


def archive(tags, skip, store, url):
    """Write manifests for [tags] and, with a store, upload them as the whole archive."""
    folder = WORK / "archive"
    objects = []

    def artifact(path, name, content_type):
        data = path.read_bytes()
        key = f"benchmarks/artifacts/sha256/{sha256(data)}/{name}"
        objects.append((path, key, content_type))
        return {"url": url + key, "sha256": sha256(data), "bytes": len(data)}

    index = {"schemaVersion": 2, "artifacts": []}
    for tag in tags:
        checkout = WORK / tag
        build_info = json.loads((WORK / f"{tag}.json").read_text())
        if git("status", "--porcelain", "--", "lib", cwd=checkout):
            raise SystemExit(f"{tag}: the release's library sources changed")
        git("add", "--intent-to-add", ".", cwd=checkout)
        patch = folder / f"{tag}.patch"
        write(patch, git("diff", "--binary", "HEAD", cwd=checkout) + "\n")
        apps = {
            kind: artifact(
                apk(tag, kind), f"{kind}.apk", "application/vnd.android.package-archive"
            )
            | {
                "package": package,
                "outputFile": f"/sdcard/Android/data/{package}/files/benchmark.log",
            }
            for kind, (_, package) in APPS.items()
        }
        manifest = {
            "schemaVersion": 2,
            "platform": "android",
            "backend": "opengl",
            "build": build_info,
            "apks": apps,
            "harness": artifact(patch, "harness.patch", "text/plain"),
            "casesMeta": publish.cases_meta(),
            "plan": [
                {"case": name, "kind": kind, "config": json.loads(config)}
                for name, kind, config in publish.plan("android")
                if name not in skip and f"{name}@{tag}" not in skip
            ],
        }
        path = folder / f"{tag}.json"
        write(path, json.dumps(manifest, indent=2) + "\n")
        index["artifacts"].append(
            {
                "release": tag,
                "commit": build_info["commit"],
                "platform": "android",
                "manifest": artifact(path, "manifest.json", "application/json"),
            }
        )
    write(folder / "index.json", json.dumps(index, indent=2) + "\n")
    if not store:
        return
    for path, key, content_type in objects:
        if fetch_hash(url + key) != sha256(path.read_bytes()):
            print(f"Uploading {key}", flush=True)
            store.put(key, path, content_type, immutable=True)
    store.put(INDEX, folder / "index.json", "application/json", immutable=False)


class Bucket(publish.Bucket):
    def put(self, key, path, content_type, immutable):
        cache = (
            "public, max-age=31536000, immutable" if immutable else "public, max-age=60"
        )
        subprocess.run(
            [
                "wrangler",
                "r2",
                "object",
                "put",
                f"{self.name}/{key}",
                "--remote",
                f"--file={path}",
                f"--content-type={content_type}",
                f"--cache-control={cache}",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
        )


def download(url):
    # Cloudflare's bot protection rejects Python's default user agent; the query skips its cache.
    request = urllib.request.Request(
        f"{url}?t={time.time_ns()}", headers={"User-Agent": "maplibre-compose-metrics"}
    )
    with urllib.request.urlopen(request, timeout=300) as response:
        return response.read()


def fetch_hash(url):
    try:
        return sha256(download(url))
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def releases(names, local, url):
    """(tag, manifest hash, manifest, APK paths) for each archived release in [names].

    The manifest hash identifies the APKs and harness patch, so runs of a rebuilt
    archive never reuse earlier captures.
    """
    if local:
        result = []
        for tag in names:
            data = (WORK / "archive" / f"{tag}.json").read_bytes()
            manifest = json.loads(data)
            apks = {kind: apk(tag, kind) for kind in APPS}
            for kind, path in apks.items():
                if sha256(path.read_bytes()) != manifest["apks"][kind]["sha256"]:
                    raise SystemExit(f"{path} was rebuilt; archive {tag} again")
            result.append((tag, sha256(data), manifest, apks))
        return result
    index = json.loads(download(url + INDEX))
    result = []
    for entry in index["artifacts"]:
        tag = entry["release"]
        if names and tag not in names:
            continue
        cache = WORK / "cache" / tag
        manifest = json.loads(
            cached(entry["manifest"], cache / "manifest.json").read_text()
        )
        apks = {
            kind: cached(record, cache / f"{kind}.apk")
            for kind, record in manifest["apks"].items()
        }
        result.append((tag, entry["manifest"]["sha256"], manifest, apks))
    return result


def cached(record, path):
    if not path.exists() or sha256(path.read_bytes()) != record["sha256"]:
        data = download(record["url"])
        if sha256(data) != record["sha256"]:
            raise SystemExit(f"{record['url']} does not match its manifest")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    return path


class Device:
    def __init__(self, serial):
        sdk = subprocess.check_output(
            [str(ROOT / ".mise/bin/android-sdk-root")], text=True
        )
        self.adb = [str(Path(sdk.strip()) / "platform-tools/adb"), "-s", serial]
        self.serial = serial

    def __call__(self, *args, check=True):
        result = subprocess.run(
            [*self.adb, *args], capture_output=True, text=True, check=False
        )
        if check and result.returncode:
            raise RuntimeError(f"adb {' '.join(args)}: {result.stderr.strip()}")
        return result.stdout.strip()

    def bytes(self, path):
        return subprocess.check_output([*self.adb, "exec-out", "cat", path])

    def thermal(self):
        report = self("shell", "dumpsys", "thermalservice")
        return int(re.search(r"^Thermal Status: (\d+)$", report, re.MULTILINE)[1])

    def cool(self):
        deadline = time.monotonic() + 1800
        while self.thermal() != 0:
            if time.monotonic() > deadline:
                raise RuntimeError("The device did not cool down in 30 minutes")
            print(f"[{self.serial}] waiting for thermal status 0", flush=True)
            time.sleep(30)


def capture(device, config, output, log_file):
    package = runner.app_package(config)
    device("shell", "am", "force-stop", package)
    device("shell", "rm", "-f", log_file)
    # Bound logcat to this launch; Android can reuse the PID of an earlier process.
    since = device("shell", "date", "+%s.%N")
    logger = None
    try:
        device(*runner.android_launch_args([], config))
        pid = device("shell", "pidof", package)
        with (output / "logcat.log").open("w") as log:
            logger = subprocess.Popen(
                [*device.adb, "logcat", f"--pid={pid}", "-T", since, "-v", "brief"],
                stdout=log,
                stderr=log,
            )

            def poll():
                (output / "app.log").write_bytes(device.bytes(log_file))

            runner.wait_for(output / "app.log", poll=poll)
    finally:
        if logger:
            runner.stop(logger)
        device("shell", "am", "force-stop", package)
    (output / "app.log").write_bytes(device.bytes(log_file))
    return analyze(output)


def measure(args):
    device = Device(args.device)
    folder = WORK / "results" / args.scope
    repeat = 1 if args.smoke else args.repeat
    awake = device("shell", "settings", "get", "global", "stay_on_while_plugged_in")
    try:
        device("shell", "settings", "put", "global", "stay_on_while_plugged_in", "7")
        device("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        for tag, archive_id, manifest, apks in releases(
            args.release, args.local, args.url
        ):
            for kind, path in apks.items():
                # An installed build signed with another key refuses the update.
                installed = device("install", "-r", str(path), check=False)
                if not installed.endswith("Success"):
                    device("uninstall", manifest["apks"][kind]["package"], check=False)
                    device("install", str(path))
            plan = [
                e for e in manifest["plan"] if not args.case or e["case"] in args.case
            ]
            cases = {}
            for name in dict.fromkeys(entry["case"] for entry in plan):
                entries = [entry for entry in plan if entry["case"] == name]
                reports = {entry["kind"]: [] for entry in entries}
                for repetition in range(repeat):
                    # Alternate which implementation runs first.
                    for entry in entries[:: 1 if repetition % 2 == 0 else -1]:
                        kind = entry["kind"]
                        config = entry["config"] | (
                            {"durationMs": 3000} if args.smoke else {}
                        )
                        output = (
                            folder
                            / ("smoke" if args.smoke else "runs")
                            / f"{tag}-{archive_id[:12]}"
                            / name
                            / kind
                            / f"{repetition + 1:03d}"
                        )
                        reports[kind].append(
                            measure_one(
                                device,
                                config,
                                output,
                                manifest["apks"][kind]["outputFile"],
                            )
                        )
                for entry in entries:
                    runs = reports[entry["kind"]]
                    for report in runs:
                        if report["build"] != manifest["build"]:
                            raise SystemExit(
                                f"{tag} {name}: the installed APK is another build"
                            )
                    cases.setdefault(name, {})[entry["kind"]] = {
                        "implementation": entry["config"]["implementation"],
                        "config": runs[0]["config"],
                        "viewport": runs[0]["viewport"],
                        "build": runs[0]["build"],
                        "metrics": publish.summarize(runs),
                        "runs": runs,
                    }
            if args.smoke or args.case:
                print(publish.table(cases), flush=True)
                continue
            snapshot = {
                "commit": manifest["build"]["commit"],
                "scope": args.scope,
                "platform": "android",
                "backend": manifest["backend"],
                "device": args.label,
                "deviceInfo": {
                    key: device("shell", "getprop", key)
                    for key in ("ro.product.model", "ro.build.fingerprint")
                },
                "measuredAt": datetime.datetime.now(datetime.timezone.utc).isoformat(
                    timespec="seconds"
                ),
                "backfill": manifest["build"]["backfill"] | {"archive": archive_id},
                "cases": cases,
            }
            write(folder / f"{tag}.json", json.dumps(snapshot, indent=2) + "\n")
            print(publish.table(cases), flush=True)
            print(f"[{args.scope}] finished {tag}", flush=True)
    finally:
        device("shell", "settings", "put", "global", "stay_on_while_plugged_in", awake)


def measure_one(device, config, output, log_file):
    """One capture while the device reports no thermal throttling, retried up to three times."""
    done = output / "accepted.json"
    if done.exists():
        return analyze(output)
    for _ in range(3):
        device.cool()
        if output.exists():
            output.rename(output.with_name(f"{output.name}-rejected-{time.time_ns()}"))
        output.mkdir(parents=True)
        print(f"[{device.serial}] {output.relative_to(WORK)}", flush=True)
        try:
            report = capture(
                device, json.dumps(config, separators=(",", ":")), output, log_file
            )
        except (RuntimeError, ValueError, subprocess.CalledProcessError) as error:
            print(f"[{device.serial}] failed: {error}", flush=True)
            continue
        status = device.thermal()
        if status == 0:
            write(done, json.dumps({"thermalStatusAfter": status}) + "\n")
            return report
        print(
            f"[{device.serial}] throttled during the run; measuring again", flush=True
        )
    raise SystemExit(f"{output} failed three times")


def publish_results(args, store):
    """Merge each finished release snapshot for one device into the page data."""
    folder = WORK / "results" / args.scope
    scope = {
        "id": args.scope,
        "label": args.label,
        "platform": "android",
        "backend": "opengl",
    }
    for tag, archive_id, manifest, _ in releases(args.release, False, args.url):
        snapshot = json.loads((folder / f"{tag}.json").read_text())
        expected = {(e["case"], e["kind"]) for e in manifest["plan"]}
        measured = {
            (name, kind) for name, case in snapshot["cases"].items() for kind in case
        }
        if snapshot["backfill"].get("archive") != archive_id:
            raise SystemExit(f"{tag}: the results measured another archive")
        if measured != expected or snapshot["device"] != args.label:
            raise SystemExit(f"{tag}: the results are incomplete or for another label")
        commit = snapshot["commit"]
        info = {
            "commit": commit,
            "date": git("show", "-s", "--format=%cI", commit),
            "title": git("show", "-s", "--format=%s", commit),
            "tags": [],
        }
        publish.sync(store, snapshot, scope, manifest["casesMeta"], info)
        print(f"Published {tag} for {args.scope}", flush=True)


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--bucket", help="R2 bucket for archive --upload and publish")
    parser.add_argument(
        "--url", required=True, help="The bucket's public URL, ending in a slash"
    )
    actions = parser.add_subparsers(dest="action", required=True)
    command = actions.add_parser("prepare")
    command.add_argument("tag")
    command.add_argument(
        "--classic",
        required=True,
        help="Classic Android SDK version current at the release",
    )
    command = actions.add_parser("build")
    command.add_argument("tag")
    command = actions.add_parser("archive")
    command.add_argument("tags", nargs="+")
    command.add_argument(
        "--skip",
        action="append",
        default=[],
        help="A case the releases cannot run, or CASE@TAG for one release",
    )
    command.add_argument(
        "--upload", action="store_true", help="Replace the archive in the bucket"
    )
    for name in ("run", "publish"):
        command = actions.add_parser(name)
        command.add_argument(
            "--scope", required=True, help="Device id, such as pixel-8-opengl"
        )
        command.add_argument(
            "--label", required=True, help="Device name shown on the page"
        )
        command.add_argument(
            "--release", action="append", help="Only this release; repeatable"
        )
    command = actions.choices["run"]
    command.add_argument("--device", required=True, help="adb serial")
    command.add_argument("--repeat", type=int, default=3)
    command.add_argument(
        "--case",
        action="append",
        help="Only this case; nothing is saved for publishing",
    )
    command.add_argument(
        "--smoke", action="store_true", help="One 3-second run of each case"
    )
    command.add_argument(
        "--local",
        action="store_true",
        help="Use the built APKs and manifests before upload",
    )
    actions.choices["publish"].add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.tag, args.classic)
    elif args.action == "build":
        build(args.tag)
    elif args.action == "archive":
        store = Bucket(args.bucket, args.url, False) if args.upload else None
        archive(args.tags, set(args.skip), store, args.url)
    elif args.action == "run":
        if args.local and not args.release:
            parser.error("--local needs --release")
        measure(args)
    else:
        publish_results(args, Bucket(args.bucket, args.url, args.dry_run))


if __name__ == "__main__":
    sys.exit(main())
