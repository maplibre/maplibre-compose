set shell := ['bash', '-euo', 'pipefail', '-c']

fmt: fmt-kotlin fmt-swift fmt-markdown fmt-yaml fmt-cpp

# Kotlin (.kt, .kts, including *.gradle.kts)
fmt-kotlin:
    #!/usr/bin/env bash
    if command -v ktfmt >/dev/null 2>&1; then
      git ls-files -z -- '*.kt' '*.kts' \
        | xargs -0 -n 50 ktfmt -i --google-style || true
    else
      echo 'Warning: ktfmt not found on PATH. Skipping Kotlin formatting.' >&2
    fi

# Swift (only formats files if `swift format` is available)
fmt-swift:
    #!/usr/bin/env bash
    if command -v swift >/dev/null 2>&1 && swift format --help >/dev/null 2>&1; then
      git ls-files -z -- 'iosApp/iosApp/**/*.swift' \
        | xargs -0 -n 50 swift format --in-place || true
    else
      echo 'Warning: swift format not available. Install swift-format or ensure `swift format` works.' >&2
    fi

# Markdown via prettier
fmt-markdown:
    #!/usr/bin/env bash
    if command -v prettier >/dev/null 2>&1; then
      git ls-files -z -- '*.md' \
        | xargs -0 -n 50 prettier --log-level warn --prose-wrap always --write || true
    else
      echo 'Warning: prettier not found on PATH. Skipping Markdown formatting.' >&2
    fi

# YAML via prettier (GitHub workflows, etc.)
fmt-yaml:
    #!/usr/bin/env bash
    if command -v prettier >/dev/null 2>&1; then
      {
        find .github -type f \( -name '*.yml' -o -name '*.yaml' \) -print0 2>/dev/null || true
      } \
        | xargs -0 -n 50 prettier --log-level warn --write || true
    else
      echo 'Warning: prettier not found on PATH. Skipping YAML formatting.' >&2
    fi

# C++ and headers via clang-format
fmt-cpp:
    #!/usr/bin/env bash
    if command -v clang-format >/dev/null 2>&1; then
      git ls-files -z -- '*.cpp' '*.hpp' '*.mm' \
        | xargs -0 -n 50 clang-format -i || true
    else
      echo 'Warning: clang-format not found on PATH. Skipping C++ formatting.' >&2
    fi


