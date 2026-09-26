"""Build release benchmark apps for the iOS simulator or a physical iPhone."""

import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--device", action="store_true", help="Build for physical iPhones"
    )
    parser.add_argument(
        "--implementation", choices=("all", "compose", "classic-ios"), default="all"
    )
    args = parser.parse_args()
    destination = "generic/platform=iOS" + ("" if args.device else " Simulator")
    apps = []
    if args.implementation in ("all", "compose"):
        apps.append(("demo-app/ios", "iosApp"))
    if args.implementation in ("all", "classic-ios"):
        subprocess.run(
            ["python", "benchmarks/prepare_ios_sdk.py"], cwd=ROOT, check=True
        )
        apps.append(("benchmarks/ios", "ClassicBenchmark"))
    for folder, scheme in apps:
        command = [
            "xcodebuild",
            "-project",
            f"{folder}/{scheme}.xcodeproj",
            "-scheme",
            scheme,
            "-configuration",
            "Release",
            "ARCHS=arm64",
            "-destination",
            destination,
            "-derivedDataPath",
            f"{folder}/build/DerivedData",
        ]
        command += (
            ["-allowProvisioningUpdates"]
            if args.device
            else ["CODE_SIGNING_ALLOWED=NO"]
        )
        # Xcode can reuse an executable after its script phase updates a static Kotlin
        # framework. Rebuild the small app host; Gradle retains its compilation cache.
        subprocess.run([*command, "clean", "build"], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
