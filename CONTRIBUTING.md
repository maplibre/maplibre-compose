# Contributing

## Clone the repo

```bash
git clone https://github.com/maplibre/maplibre-compose.git
```

## Find or file an issue to work on

If you're looking to add a feature or fix a bug and there's no issue filed yet,
it's good to
[file an issue](https://github.com/maplibre/maplibre-compose/issues/new/choose)
first to have a discussion about the change before you start working on it.

If you're new and looking for things to contribute, see our
[good first issue](https://github.com/maplibre/maplibre-compose/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22good%20first%20issue%22)
label. These issues are usually ready to work on and don't require deep
knowledge of the library's internals.

If you have particular knowledge of MapLibre, Android, iOS, or anything else
relevant, see the
[help wanted](https://github.com/maplibre/maplibre-compose/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22help%20wanted%22)
label. These are issues that need input or guidance from folks with deeper
expertise on some topic.

## Note on AI usage

If you use AI assistance, follow the [AI policy](./AI_POLICY.md).

## Set up your development environment

### Mise

This project uses [mise](https://mise.jdx.dev/) to manage its development
environment.

#### Option 1: use mise (recommended)

1. Install mise if you haven't already:
   https://mise.jdx.dev/getting-started.html.
2. Run `mise install` in the project root to install all required tools.
3. Still read the rest of the guide, because not all tools are managed by mise.

`mise install` gives you the versions CI uses. `mise.toml` pins every tool and
`mise.lock` records a checksum per platform.

`mise tasks` lists every task. CI runs these same tasks, so a green
`mise run check` locally means the same thing as a green CI job.

#### Option 2: manual setup

If you prefer not to use mise, check `mise.toml` for the list of required tools
and versions, then install them manually.

### Kotlin Multiplatform

Check out
[the official instructions](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-setup.html)
for setting up a Kotlin Multiplatform environment.

### IDE

As there's no stable LSP for Kotlin Multiplatform, you'll want to use either
IntelliJ IDEA or Android Studio for developing MapLibre Compose. In addition to
the IDE, you'll need some plugins:

- [Kotlin Multiplatform](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform)
- [Android](https://plugins.jetbrains.com/plugin/22989-android)
- [Jetpack Compose](https://plugins.jetbrains.com/plugin/18409-jetpack-compose)

### Building for Android

If you already have an SDK, from Android Studio or elsewhere, point Gradle at it
with a `local.properties` in the root of the project:

```properties
# Replace the path with the actual path on your machine
sdk.dir=/Users/username/Library/Android/sdk
```

`mise run android-sdk-packages` adds the packages the build needs to it.

For a machine with no SDK, mise pins one. It is a separate environment because
installing an SDK package accepts its license:

```bash
mise -E android install
```

Run Android builds in that environment, as
`MISE_ENV=android mise run test:android`. Set `MISE_ENV=android` in your shell
to use it for the session.

### Building for Apple platforms

Building for Apple platforms needs Xcode. `mise install` fetches only the
`xcodes` CLI that manages it, because Xcode itself is several gigabytes. Ask for
Xcode explicitly:

```bash
mise run install-xcode
```

If installing manually, use the version named in the
[`.xcode-version`](.xcode-version) file.

### Building for Desktop

Desktop consumes the published
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi) Kotlin
Multiplatform bindings. Unlike a source build of MapLibre Native, it needs no
C++ toolchain, CMake, or vendored checkout.

The desktop tests drive a real GPU through a headless Vulkan device. On macOS
the test runtime supplies MoltenVK through LWJGL, and CI installs a software
Vulkan driver on Linux and Windows. A host with no usable Vulkan implementation
fails these tests rather than skipping them.

`mise run build:desktop-app` packages a host installer: an AppImage on Linux, a
DMG on macOS, and an MSI on Windows. Linux packaging uses the mise-pinned
`appimagetool` and does not need fakeroot or dpkg. The Linux task also writes a
`.tar` next to the AppImage so the CI artifact keeps the execute bit.

## Run the demo

Use IntelliJ or Android Studio to launch the demo app on Android and XCode to
launch on iOS. Every other host has a task:

- Android: `mise run demo:android`
- Desktop: `mise run demo:desktop`
- Web: `mise run demo:js`
- Desktop on the compose-glfw host instead of the AWT one:
  `mise run demo:desktop-glfw`
- Desktop on the Nucleus Tao host instead of the AWT one:
  `mise run demo:desktop-nucleus`

The desktop demos and test suite take `--backend <name>` to package a different
Map render backend than the platform default, as in
`mise run demo:desktop -- --backend=opengl`. It passes the
`maplibre.desktop.backend` Gradle property, which swaps the packaged
`maplibre-compose-runtime-*` artifact.

## Run the tests

CI runs these same tasks, so you can reproduce a failure with the command the
job ran:

- `mise run test:android` — Android host (JVM) suite
- `mise run test:android:device [api-level]` — instrumented suite
- `mise run test:ios`
- `mise run test:js`
- `mise run test:desktop`

The device suites bring their own device. `test:android:device` boots a headless
emulator for the API level you name, and installs the emulator and system image
on first use. It passes `-Pmaplibre.android.abis=` for this host so the test
APKs carry only that JNI ABI; published AARs still carry every ABI. If a session
install hangs, the task reboots the emulator and retries once. `test:ios` boots
an iPhone simulator and runs against it.

You can drive the emulator on its own:

```bash
mise run android-emulator:boot 24
mise run android-emulator:boot 24 --headless
mise run android-emulator:stop
```

The boot task opens the emulator window by default. Pass `--headless` to run it
without a window. The AVD lives under `build/android-emulator`, so removing the
build tree removes the device.

## Building documentation

`mise run build:docs` builds the Starlight site and the Dokka API reference into
`docs/dist`. `mise run //docs:dev` serves the same site with live reload.

The site is a pnpm workspace and its own mise config root, so its tasks run as
`//docs:<task>`. `//docs:api` generates the Dokka reference into
`docs/public/api/`, and `//docs:versions` writes the versions the pages quote
into `docs/src/generated/versions.json`. Both are generated rather than checked
in, and the `dev`, `build`, and `preview` tasks depend on them.

Use the tasks rather than Astro or Gradle directly. They pass the versions
derived from the Git tags, which the site prints as the coordinates to depend
on; Gradle on its own uses the `0.0.0` placeholders from `gradle.properties`.

## Make CI happy

`mise run check` reports problems and `mise run fix` rewrites what it can.
Between them they cover dprint, actionlint, ruff, shellcheck, the GitHub Actions
pins catalog, JSON schema validation, and the documentation site's type check.
`mise run lint:android` runs Android Lint, which CI runs in the same job.

A Git pre-commit hook runs the same steps against your staged files. `mise`
installs it for you. Remove it with:

```bash
hk uninstall
```

## Versions

Every version the build pins — dependencies, plugins, Android SDK levels, and
JVM targets — lives in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
`gradle.properties` holds build switches only.

Releases are tagged `vMAJOR.MINOR.PATCH`. `gradle.properties` carries
placeholder versions, and `.mise/bin/version-args` derives the real ones from
the tags. Only the tasks that publish or document a version pass them to Gradle,
so an ordinary build needs no tags in the checkout.

```bash
mise run version            # what this commit would build as
mise run version snapshot   # what the nightly job would publish
mise run version release    # what the release workflow would publish
```

A tagged commit builds as that release; every other commit builds as a snapshot
of the next patch.

### GitHub Actions pins

Every third-party action is pinned to a commit SHA, and every one of those pins
is declared once in
[`.github/workflows/action-pins.yml`](.github/workflows/action-pins.yml). That
file never runs. It exists so that Dependabot sees the actions that the
composite actions under `.github/actions` use, which it would otherwise skip.
`mise run ci:check-action-pins` fails when a reference anywhere disagrees with
the catalog, so an update lands in one place and propagates from there.
