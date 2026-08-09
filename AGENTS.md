## Pull requests

When you open a pull request, write **Description** and **Test plan** in at most
one sentence of prose each. I will expand the PR description if more detail is
needed. More context: [AI_POLICY.md](./AI_POLICY.md).

## Development commands

A task you run locally is the command CI runs. mise defines those tasks, pins
every tool in `mise.toml`, and locks them per platform in `mise.lock`.
`mise tasks` lists them all.

### Building and running

Run a mise task rather than `./gradlew build`. The aggregate Gradle task builds
every target at once, including the iOS release frameworks, and runs out of
memory before it finishes. Each task below covers one platform, so pick the one
for the change you made.

- **Package the Android APK:** `mise run build:android-app`
- **Package the desktop installer:** `mise run build:desktop-app`
- **Run desktop demo:** `mise run demo:desktop`
- **Run web demo:** `mise run demo:js`
- **Run the demo on the compose-glfw host:** `mise run demo:desktop-glfw`
- **Clean build:** `mise run clean`

To compile one module, name its task:
`./gradlew :lib:maplibre-compose:assemble`.

### Formatting and linting

- **Report problems:** `mise run check`
- **Fix what can be fixed:** `mise run fix`
- **Android Lint:** `mise run lint:android`

dprint formats every language in the repository, configured in `dprint.jsonc`.
hk runs it, and runs actionlint, ruff, shellcheck, the Actions pins check, JSON
schema validation, and the documentation site's type check. `hk.pkl` lists the
steps.

### Documentation

- **Generate docs:** `mise run build:docs` (Starlight site and Dokka API
  reference, into `docs/dist`)
- **Serve docs:** `mise run //docs:dev`

The site is a pnpm workspace and its own mise config root, so its tasks run as
`//docs:<task>`. Build the docs through a task. It passes the versions derived
from the Git tags, which the site prints as the coordinates to depend on; Gradle
on its own uses the `0.0.0` placeholders from `gradle.properties`.

### Material Symbols

Find Android vector XML under
[`symbols/android/<icon-name>/`](https://github.com/google/material-design-icons/tree/master/symbols/android)
in Google's official Material Design Icons repository.

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
  - `common`: Every line of the app, and the only Kotlin Multiplatform module
  - `android`: An Android application that launches `common`
  - `desktop`: A JVM application that launches `common` on the AWT host
  - `desktop-glfw`: The same JVM application on the compose-glfw host. A module
    of its own so that its `MainDispatcherFactory`, which outranks
    `kotlinx-coroutines-swing`, stays off the AWT runtime classpath.
  - `ios`: An Xcode project that embeds the framework `common` produces

  The browser app has no module of its own. Its entry point and page live in
  `common/src/jsMain`, because a Kotlin/JS module would have to be a second
  Kotlin Multiplatform module.
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
