## Pull requests

When you open a pull request, adhere to the
[PULL_REQUEST_TEMPLATE.md](./.github/PULL_REQUEST_TEMPLATE.md) and open it in
draft mode. The user is responsible for additional details and marking ready for
review.

## Development commands

Mise defines the tasks you run locally, pins every tool in `mise.toml`, and
locks them per platform in `mise.lock`. `mise tasks` lists them all.

List all tasks with `mise tasks --all`.

Run a mise task rather than `./gradlew build`. The aggregate Gradle task builds
every target at once, including the iOS release frameworks, and runs out of
memory before it finishes. If you must run a Gradle task directly, name one task
per `./gradlew` invocation. Two together can fail in ways neither does alone.

### Building and running

- `mise run build:desktop-app`
- `mise run build:android-app`
- `mise run build:ios:device`
- `mise run demo:desktop`
- `mise run demo:desktop-glfw`
- `mise run demo:android` (prompts when several devices are connected;
  `--backend vulkan` packages the Vulkan runtime)
- `mise run demo:ios` (pass `--device` for a connected iPhone; prompts when
  several are ready; `--release` builds the optimized framework)
- `mise run demo:js`

### Formatting and linting

- **Report problems:** `mise run check`
- **Fix what can be fixed:** `mise run fix`
- **Android Lint:** `mise run lint:android`

dprint formats every language in the repository, configured in `dprint.jsonc`.
hk runs it, and runs actionlint, ruff, shellcheck, the Actions pins check, JSON
schema validation, and the documentation site's type check. `hk.pkl` lists the
steps.

### Style spec

`mise run style-spec:parity` compares the layer API with the pinned MapLibre
style spec release at the pinned engines. `--check` fails when an in-scope layer
type, source type, paint or layout property, or native unsupported-table row is
missing. Follow the `style-spec-parity` skill in `.agents/skills/` to add one.

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

For a machine with no SDK, install the pinned SDK with
`mise -E android install`. Run Android tasks in the same environment, such as
`mise -E android run test:android`. The `android` environment sets
`ANDROID_HOME` for each command.

### Testing

- **Android host:** `mise run test:android`
- **Android device:** `mise run test:android:device [api-level]` (boots its own
  headless emulator; `android-emulator:boot` opens a window by default)
- **iOS:** `mise run test:ios` (boots its own simulator)
- **Web:** `mise run test:js`
- **Desktop:** `mise run test:desktop` (add `--backend <name>` to package a
  non-default render backend, e.g. `opengl` on Linux)

Tests live in platform-specific source sets:

- Android device tests: `src/androidDeviceTest`
- Android host tests: `src/androidHostTest`
- iOS tests: `src/iosTest`
- Common tests: `src/commonTest`
- Live-map tests: `src/liveMapTest`
- Browser tests: `src/jsTest`

`liveMapTest` runs on every platform that hosts a MapLibre runtime. Those tests
stay out of `commonTest` because `androidHostTest` inherits that source set and
has no MapLibre runtime and no Compose UI test host.

The browser tests drive a real map in headless Chrome. They need `CHROME_BIN` if
Karma cannot find one, and they fail as timeouts rather than assertion
mismatches if the machine idles, because that stalls `requestAnimationFrame` —
so run them under `caffeinate -dimsu` on macOS. `--tests` silently runs nothing
and still reports success.

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
  - `location`: Location and orientation providers, usable without a map
  - `location-runtime-gms|hms|linux|macos|windows`: Location backends that
    `ServiceLoader` discovers; gms upgrades Android location and orientation
    through Google Play services, hms upgrades Android location through HMS
    Core, and the desktop backends supply the only desktop implementations
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

- **Android/Desktop/iOS**: MapLibre Native Core via
  [`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi)
- **Web**: MapLibre GL JS, declared in `org.maplibre.compose.gljs`; the upstream
  types it mirrors are at
  `build/js/node_modules/maplibre-gl/dist/maplibre-gl.d.ts`
