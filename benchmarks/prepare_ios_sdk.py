"""Fetch the pinned classic iOS SDK for Kotlin cinterop and Xcode packaging."""

import shutil
import tempfile
import urllib.request
import zipfile
from pathlib import Path

import tomllib

ROOT = Path(__file__).resolve().parent.parent


def main():
    version = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())[
        "versions"
    ]["maplibre-ios"]
    destination = ROOT / "benchmarks/ios/build/sdk"
    marker = destination / ".version"
    if marker.exists() and marker.read_text() == version:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as directory:
        archive = Path(directory) / "sdk.zip"
        urllib.request.urlretrieve(
            "https://github.com/maplibre/maplibre-native/releases/download/"
            f"ios-v{version}/MapLibre.dynamic.xcframework.zip",
            archive,
        )
        extracted = Path(directory) / "sdk"
        with zipfile.ZipFile(archive) as sdk:
            sdk.extractall(extracted)
        if destination.exists():
            shutil.rmtree(destination)
        shutil.move(extracted, destination)
        marker.write_text(version)


if __name__ == "__main__":
    main()
