#!/usr/bin/env bash
set -euo pipefail

args=()
for arg in "$@"; do
  if [[ "$arg" != "--disable-sandbox" ]]; then
    args+=("$arg")
  fi
done

exec xcrun --sdk macosx swift "${args[@]}"
