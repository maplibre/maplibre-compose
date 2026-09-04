# Repository guidance

MapLibre Compose wraps MapLibre for Kotlin Multiplatform. Native platforms use
`maplibre-native-ffi`; the browser uses hand-written MapLibre GL JS bindings.

## Development

Use mise tasks. `mise tasks --all` lists commands and `CONTRIBUTING.md` covers
setup, demo launch, packaging, and documentation builds. `mise.toml` defines the
tasks and tool versions; `mise.lock` locks the tools per platform.

Avoid `./gradlew build`: it builds all targets, including iOS release
frameworks, and can exhaust memory. For a task that mise does not provide, run
one named Gradle task per invocation.

- Static checks: `mise run check`; automatic fixes: `mise run fix`.
- Android Lint: `mise run lint:android`.
- Tests: `mise run test:android`, `test:android:device`, `test:ios`, `test:js`,
  or `test:desktop`. Select the platforms affected by the change.
- Documentation: `mise run build:docs` or `mise run //docs:dev`. These tasks
  supply versions derived from Git tags; direct Gradle builds use placeholders.

Keep verification proportional to the change. Extend existing tests for changed
behavior; add coverage where it catches a regression. Report what ran, its
result, and any untested platform or behavior that matters to the change.

### Test and environment constraints

`liveMapTest` needs a MapLibre runtime and Compose UI test host. Keep these
tests out of `commonTest`, which Android host tests inherit. Android host and
device tests live in `androidHostTest` and `androidDeviceTest` respectively.

Browser tests run real maps in Chrome. Set `CHROME_BIN` if Karma cannot find it.
Do not pass `--tests` to the browser suite; it silently runs no tests and
reports success.

Android SDK lookup is `local.properties`, then `ANDROID_HOME`, then
`ANDROID_SDK_ROOT`. `mise run android-sdk-packages` installs required packages.
Without an existing SDK, use `mise -E android install` and run Android tasks in
that environment, for example `mise -E android run test:android`.

### Build conventions

Dependency, plugin, Android SDK, and JVM versions belong in
`gradle/libs.versions.toml`. `gradle.properties` holds build switches and
placeholder release versions. `.mise/bin/version-args` derives published
versions from `vMAJOR.MINOR.PATCH` tags; keep Git access outside Gradle
configuration.

CI jobs call mise tasks. Change a job's build or test command in the task.
Third-party action SHAs are declared in `.github/workflows/action-pins.yml`;
`mise run ci:check-action-pins` verifies their consumers. `hk.pkl` defines the
static checks and `dprint.jsonc` configures formatting.

## Architecture and task guidance

`demo-app/common` is the demo's only Kotlin Multiplatform module and contains
the shared app. Android, AWT desktop, Nucleus desktop, and iOS modules launch
it. The browser entry point is in `common/src/jsMain`.

- For repository prose and KDoc, use
  [docs-writing](.agents/skills/docs-writing/SKILL.md).
- For a MapLibre GL JS upgrade, use
  [bump-maplibre-gl-js](.agents/skills/bump-maplibre-gl-js/SKILL.md).
- For style properties, layer or source types, and engine support changes, use
  [style-spec-parity](.agents/skills/style-spec-parity/SKILL.md).
- For Material Symbols, use the Android vector XML in Google's
  [symbols/android](https://github.com/google/material-design-icons/tree/master/symbols/android).

## Pull requests

Follow [PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) and
[AI_POLICY.md](AI_POLICY.md). Explain what reviewers need to understand the
change, with detail proportional to its complexity.

Use draft status for unfinished work, unresolved decisions, or pending human
review of generated code.
