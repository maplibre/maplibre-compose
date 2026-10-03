"""Compile the benchmark-only Metal observer for one Kotlin/Native target."""

import subprocess
import sys
from pathlib import Path

sdk, target, output = sys.argv[1:]
output = Path(output)
output.parent.mkdir(parents=True, exist_ok=True)
source = Path(__file__).parent / "src/nativeInterop/cinterop/MetalFrames.m"
sdk_root = subprocess.check_output(
    ["xcrun", "--sdk", sdk, "--show-sdk-path"], text=True
).strip()
obj = output.with_suffix(".o")
subprocess.run(
    [
        "xcrun",
        "clang",
        "-isysroot",
        sdk_root,
        "-target",
        target,
        "-fobjc-arc",
        "-O2",
        "-c",
        source,
        "-o",
        obj,
    ],
    check=True,
)
subprocess.run(["xcrun", "ar", "rcs", output, obj], check=True)
