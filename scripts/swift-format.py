#!/usr/bin/env python3
"""
Swift formatter wrapper that works cross-platform.
Tries to find and use either 'swift format' or 'swift-format' command.
"""

import sys
import subprocess
import shutil
from typing import List, Optional


def find_swift_formatter() -> Optional[List[str]]:
    # Try swift format first (newer, built into Swift toolchain)
    if shutil.which("swift"):
        try:
            # Check if 'swift format' subcommand is available
            result = subprocess.run(
                ["swift", "format", "--help"],
                capture_output=True,
                text=True,
                timeout=10,
            )
            if result.returncode == 0:
                return ["swift", "format"]
        except (subprocess.TimeoutExpired, FileNotFoundError):
            pass

    # Try swift-format as fallback
    if shutil.which("swift-format"):
        return ["swift-format"]

    return None


def format_files(files: List[str]) -> int:
    if not files:
        return 0

    formatter = find_swift_formatter()
    if not formatter:
        print("swift format or swift-format not found", file=sys.stderr)
        return 1

    # Format files in-place
    cmd = formatter + ["-i"] + files

    try:
        result = subprocess.run(cmd, check=False)
        return result.returncode
    except Exception as e:
        print(f"Error running Swift formatter: {e}", file=sys.stderr)
        return 1


def main():
    files = sys.argv[1:]
    return format_files(files)


if __name__ == "__main__":
    sys.exit(main())
