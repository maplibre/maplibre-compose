"""Verify native ABI coverage and link dependencies in a packaged Android demo."""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import tempfile
import zipfile

ABIS = {
    "armeabi-v7a": ("ELF32", "ARM", "arm-linux-androideabi"),
    "arm64-v8a": ("ELF64", "AArch64", "aarch64-linux-android"),
    "x86_64": ("ELF64", "Advanced Micro Devices X86-64", "x86_64-linux-android"),
}
MAP_LIBRARIES = {"libjniMaplibreNativeC.so", "libmaplibre-native-c.so"}
SHIM = "libmaplibre_compose_vulkan.so"


def check_apk(apk: pathlib.Path, ndk: pathlib.Path, min_sdk: int, backend: str) -> None:
    (toolchain,) = (ndk / "toolchains/llvm/prebuilt").iterdir()
    readelf = toolchain / "bin/llvm-readelf"
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temporary:
        libraries: dict[str, dict[str, str]] = {}
        for name in archive.namelist():
            parts = pathlib.PurePosixPath(name).parts
            if len(parts) == 3 and parts[0] == "lib" and parts[2].endswith(".so"):
                libraries.setdefault(parts[1], {})[parts[2]] = name
        if set(libraries) != set(ABIS):
            raise ValueError(f"{apk}: expected ABIs {set(ABIS)}, got {set(libraries)}")
        # ARMv7 must include every native dependency shipped for either 64-bit ABI.
        # Some optional HMS libraries exist only for ARM, so the reverse is not required.
        all_names = set().union(*(set(items) for items in libraries.values()))
        missing = all_names - libraries["armeabi-v7a"].keys()
        if missing:
            raise ValueError(f"{apk}: missing ARMv7 libraries: {sorted(missing)}")
        for abi, (elf_class, machine, triple) in ABIS.items():
            required = MAP_LIBRARIES | ({SHIM} if backend == "vulkan" else set())
            missing = required - libraries[abi].keys()
            if missing:
                raise ValueError(f"{apk}: {abi} missing {sorted(missing)}")
            if backend == "opengl" and SHIM in libraries[abi]:
                raise ValueError(
                    f"{apk}: OpenGL APK unexpectedly contains the Vulkan shim"
                )
            # API-specific NDK stubs are the public system libraries at our SDK floor.
            # libc++_shared.so is not a system library; it must be packaged if needed.
            system_dir = toolchain / "sysroot/usr/lib" / triple / str(min_sdk)
            system_libraries = {p.name for p in system_dir.glob("*.so")}
            if not system_libraries:
                raise ValueError(f"No NDK system libraries at {system_dir}")
            for library, member in sorted(libraries[abi].items()):
                path = archive.extract(member, temporary)
                output = subprocess.check_output(
                    [str(readelf), "--file-header", "--dynamic", path], text=True
                )
                actual_class = re.search(r"Class:\s+(\S+)", output)
                actual_machine = re.search(r"Machine:\s+([^\n]+)", output)
                if (
                    actual_class is None
                    or actual_class[1] != elf_class
                    or actual_machine is None
                    or actual_machine[1].strip() != machine
                ):
                    raise ValueError(f"{member}: incorrect ELF architecture\n{output}")
                needed = set(re.findall(r"\(NEEDED\).*\[([^]]+)\]", output))
                unresolved = needed - libraries[abi].keys() - system_libraries
                if unresolved:
                    raise ValueError(
                        f"{member}: unpackaged dependencies {sorted(unresolved)}"
                    )
                print(
                    f"{apk.name}: {abi}/{library}: {elf_class}, needs {', '.join(sorted(needed))}"
                )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--ndk", type=pathlib.Path, required=True)
    parser.add_argument("--min-sdk", type=int, required=True)
    parser.add_argument("--backend", choices=["opengl", "vulkan"], required=True)
    args = parser.parse_args()
    check_apk(args.apk, args.ndk, args.min_sdk, args.backend)


if __name__ == "__main__":
    main()
