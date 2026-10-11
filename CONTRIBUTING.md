# Contributing

For a feature or a bug fix without an issue,
[file one](https://github.com/maplibre/maplibre-compose/issues/new/choose) first
to discuss the change. New contributors can start with
[good first issues](https://github.com/maplibre/maplibre-compose/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22good%20first%20issue%22);
[help wanted](https://github.com/maplibre/maplibre-compose/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22help%20wanted%22)
issues need MapLibre, Android, iOS, or other expertise.

If you use AI assistance, follow the [AI policy](./AI_POLICY.md).

## Set up

Install [mise](https://mise.jdx.dev/getting-started.html) and run `mise install`
for the tool versions CI uses. `mise tasks` lists every task, and CI runs the
same tasks, so a local failure reproduces with the command the job ran.

Use IntelliJ IDEA or Android Studio with the
[Kotlin Multiplatform](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform),
[Android](https://plugins.jetbrains.com/plugin/22989-android), and
[Jetpack Compose](https://plugins.jetbrains.com/plugin/18409-jetpack-compose)
plugins; Kotlin Multiplatform has no stable LSP.

Some tools stay outside `mise install`:

- **Android SDK:** point `sdk.dir` in `local.properties` at an existing SDK and
  run `mise run android-sdk-packages`. Without one, `mise -E android install`
  installs the pinned SDK in a separate environment, because installing SDK
  packages accepts their licenses; run Android tasks with `MISE_ENV=android`.
- **Xcode:** `mise run install-xcode`. It is several gigabytes, so
  `mise install` only fetches the `xcodes` CLI that manages it.
- **Vulkan:** the desktop tests drive a real GPU through a headless Vulkan
  device and fail on a host without one. macOS gets MoltenVK through LWJGL.

Desktop uses the published
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi)
bindings, so it needs no C++ toolchain. The browser embeds a patched MapLibre GL
JS that Gradle builds automatically; see the
[patch workflow](patches/maplibre-gl-js/README.md) to change it. The
[benchmark guide](benchmarks/README.md) covers performance measurements.

## Check changes by hand

CI tests the AWT desktop host. For desktop bridge changes, also run
`demo:desktop` and `demo:desktop-nucleus` with each backend and check resize,
zoom, pan, and window closure.

The browser demo serves a Compose HTML ferry departure board at `/?ferries`.

### Packaged demos

Download demo artifacts from the **Artifacts** section of a GitHub Actions run.
They are retained for seven days. Draft PRs produce Android, browser, and Linux
x64 packages; ready PRs also produce macOS, Windows x64, and iOS simulator
packages. The `ci:full` label adds Linux and Windows ARM64 packages.

| Platform                                 | Local task (`mise run …`) | CI artifact                       | Contents                         |
| ---------------------------------------- | ------------------------- | --------------------------------- | -------------------------------- |
| Android phone, tablet, TV, Auto, Wear OS | `build:android-app`       | `demo-app-android`                | Debug-key-signed release APKs    |
| Browser                                  | `package:js-app`          | `demo-app-browser`                | Static site in `.tar.gz`         |
| Linux x64 / ARM64                        | `build:desktop-app`       | `demo-app-desktop-linux-<arch>`   | Nucleus `.AppImage` in `.tar.gz` |
| Windows x64 / ARM64                      | `build:desktop-app`       | `demo-app-desktop-windows-<arch>` | Nucleus NSIS `.exe` installer    |
| macOS ARM64                              | `build:desktop-app`       | `demo-app-desktop-macos-arm64`    | Nucleus `.dmg` with bundled JVM  |
| macOS ARM64, Kotlin/Native host          | `build:macos-app`         | `demo-app-macos-native-arm64`     | `.app` in `.tar.gz`              |
| iOS simulator ARM64, including CarPlay   | `build:ios-simulator-app` | `demo-app-ios-simulator-arm64`    | Simulator `.app` in `.tar.gz`    |

Desktop packages build for the host OS and architecture and include its default
MapLibre backend. They use
[Nucleus packaging](https://nucleusframework.dev/en/docs/packaging/); the AWT
host remains available through `demo:desktop` for development and tests. The
version comes from Git tags. macOS bundle versions add one to the major version
because `jpackage` rejects a zero major version. Packages use ad hoc signing on
macOS, without notarization, and no publisher signature on Windows.

Extract the Linux archive before launching the AppImage so its executable
permission is preserved. Open the Windows installer, or copy the macOS app from
the DMG to Applications. The native macOS archive can be extracted and its app
launched directly. Install Android APKs with `adb install <apk>`; Auto and Wear
OS use their own APKs. The phone APK also supports Android TV.

Extract the browser archive and serve its directory over HTTP, for example with
`python3 -m http.server`; opening `index.html` as a file does not work.

The iOS archive runs on an Apple Silicon simulator with a compatible iOS runtime
and requires no provisioning profile. Extract it, boot a simulator, and run:

```sh
xcrun simctl install booted maplibre-compose-demo.app
xcrun simctl launch booted org.maplibre.compose.demoapp --route=demo/castello-plan
```

Physical iOS devices require a signed, provisioned build made with Xcode.

### Android Auto

`mise run demo:android-auto` needs the
[Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu):

1. On a connected phone, install and update Android Auto, enable its developer
   mode, allow unknown sources, and start the head unit server from its
   developer menu.
2. Install **Android Auto Desktop Head Unit Emulator** from Android Studio's SDK
   Tools, or run `sdkmanager 'extras;google;auto'`.
3. Run the task and select **MapLibre** in the car launcher.

For Surface integration changes, check map rendering, pan/zoom, recentering,
day/night mode, window resizing, and reconnecting the host.

### CarPlay

Run `mise run demo:ios`, choose **I/O > External Displays > CarPlay** in
Simulator, and open **maplibre-compose-demo** in the car launcher. Check map
rendering, pan/zoom, recentering, day/night mode, and reconnecting the CarPlay
display while the phone app stays open.

Only simulator builds carry the CarPlay maps entitlement. A physical CarPlay
connection needs Apple's
[CarPlay entitlement approval](https://developer.apple.com/documentation/carplay/requesting-carplay-entitlements),
a matching provisioning profile, and `CODE_SIGN_ENTITLEMENTS` set to
`iosApp/CarPlay.entitlements` for the device SDK.

## Documentation and versions

Build the site with `mise run build:docs` or serve it with `mise run //docs:dev`
rather than calling Astro or Gradle directly. The tasks pass versions derived
from Git tags, which the site quotes as dependency coordinates; Gradle alone
uses the `0.0.0` placeholders from `gradle.properties`.

Releases are tagged `vMAJOR.MINOR.PATCH`. Any other commit builds as a snapshot
of the next patch; `mise run version` prints what this checkout builds as.

## Make CI happy

`mise run check` reports problems and `mise run fix` rewrites what it can; the
pre-commit hook that mise installs runs them on staged files.
`mise run lint:android` runs Android Lint, which CI runs in the same job.

Public API changes follow the
[API guidelines](docs/src/content/docs/api-guidelines.md).

On macOS with Xcode, `mise run api:check` compares the published Kotlin APIs
with the committed dumps in each module's `api/` directory. After an intentional
API change, run `mise run api:update`, review the diff, and include it in the
same pull request.

## Pull requests

Prefer Conventional Commits for PR titles, which become the squash-merge commit
message.
