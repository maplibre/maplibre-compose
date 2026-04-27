#!/usr/bin/env bash
set -euo pipefail

args=()
# Work around spmForKmp binary XCFramework resolution failure:
# https://github.com/frankois944/spm4Kmp/issues/312
for arg in "$@"; do
  if [[ "$arg" != "--disable-sandbox" ]]; then
    args+=("$arg")
  fi
done

exec xcrun --sdk macosx swift "${args[@]}"
