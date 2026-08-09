## Pull requests

When you open a pull request, write **Description** and **Test plan** in at most
one sentence of prose each. I will expand the PR description if more detail is
needed. More context: [AI_POLICY.md](./AI_POLICY.md).

## Development commands

A task you run locally is the command CI runs. mise defines those tasks, pins
every tool in `mise.toml`, and locks them per platform in `mise.lock`.
`mise tasks` lists them all.

### Building and running

- **Build all modules:** `mise run build`
- **Run desktop demo:** `mise run demo:desktop`
- **Run web demo:** `mise run demo:js`
- **Run the desktop host fixture:** `mise run run:glfw-fixture`
- **Clean build:** `mise run clean`

### Formatting and linting

- **Report problems:** `mise run check`
- **Fix what can be fixed:** `mise run fix`
- **Android Lint:** `mise run lint:android`

dprint formats every language in the repository, configured in `dprint.jsonc`.
hk runs it, and runs actionlint, ruff, shellcheck, the Actions pins check, and
JSON schema validation. `hk.pkl` lists the steps.

### Documentation

- **Generate docs:** `mise run build:docs` (MkDocs site and Dokka API reference)

Build the docs through the task. It passes the versions derived from the Git
tags, which the site prints as the coordinates to depend on; Gradle on its own
uses the `0.0.0` placeholders from `gradle.properties`.

### Versions

Every version the build pins lives in `gradle/libs.versions.toml`, including the
Android SDK levels and JVM targets. `gradle.properties` is for build switches
and the version placeholders below; do not add versions to it.

Releases are tagged `vMAJOR.MINOR.PATCH`. `gradle.properties` holds placeholder
versions. `.mise/bin/version-args` derives the real ones from the tags, and the
tasks that publish or document a version pass them to Gradle. Run
`mise run version [build|snapshot|release]` to see what a mode produces.

The build itself never reads Git. Do not reintroduce a Gradle plugin that reads
it at configuration time.

### Android SDK

The build reads `local.properties`, then `ANDROID_HOME`, then
`ANDROID_SDK_ROOT`, so an SDK from Android Studio or a CI runner image works as
it is. `mise run android-sdk-packages` adds the packages the build needs to that
SDK.

For a machine with no SDK, `mise -E android install` pins one. It is a separate
environment because installing an SDK package accepts its license.

### Testing

- **Android host:** `mise run test:android`
- **Android device:** `mise run test:android:device [api-level]` (boots its own
  headless emulator; `android-emulator:boot`/`:stop` drive it directly)
- **iOS:** `mise run test:ios` (boots its own simulator)
- **Web:** `mise run test:js`
- **Desktop:** `mise run test:desktop`

Tests live in platform-specific source sets:

- Android device tests: `src/androidDeviceTest`
- Android host tests: `src/androidHostTest`
- iOS tests: `src/iosTest`
- Common tests: `src/commonTest`

### CI

Workflows live in `.github/workflows`. Each job covers one platform, and
`hygiene` covers all static analysis. Every job installs its toolchain through
`.github/actions/setup-ci-deps` and then runs a mise task. Change what a job
does by changing the task rather than the YAML.

Third-party actions are pinned to commit SHAs.
`.github/workflows/action-pins.yml` declares every pin once, and
`mise run ci:check-action-pins` fails when a reference disagrees with it.

## Writing

Follow the `docs-writing` skill in `.agents/skills/` for all prose: the
documentation site, KDoc, and this file. It covers sentence style and page
structure.

## Architecture overview

MapLibre Compose is a Kotlin Multiplatform wrapper around MapLibre SDKs for
rendering interactive maps across Android, iOS, Desktop, and Web platforms.

### Project structure

- **`lib/`**: Core library modules
  - `maplibre-compose`: Main map composables and core functionality
  - `maplibre-compose-material3`: Material 3 themed UI components
  - `maplibre-compose-gms`: Google location services components
  - `maplibre-js-bindings`: Kotlin/JS bindings for MapLibre GL JS
    - This wraps the TypeScript library whose original types are available at
      build/js/node_modules/maplibre-gl/dist/maplibre-gl.d.ts
- **`demo-app/`**: Multiplatform demo application
- **`iosApp/`**: iOS-specific demo app wrapper
- **`buildSrc/`**: Custom Gradle build conventions

### Key packages

- `org.maplibre.compose.map`: Core map composable and components
- `org.maplibre.compose.camera`: Camera controls and positioning
- `org.maplibre.compose.layers`: Layer composables for map visualization
- `org.maplibre.compose.sources`: Data source composables
- `org.maplibre.compose.expressions`: DSL for MapLibre expressions
- `org.maplibre.compose.offline`: Offline map data management
- `org.maplibre.compose.location`: Location engine

### Platform implementation

The library uses platform-specific implementations:

- **Android/iOS**: MapLibre Native SDKs (MapLibre Android SDK, MapLibre iOS)
- **Web**: MapLibre GL JS via `maplibre-js-bindings`
- **Desktop**: MapLibre Native Core via
  [`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi)
