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

This project uses [mise](https://mise.jdx.dev/) for environment management. You
can either:

#### Option 1: Use mise (Recommended)

1. Install mise if you haven't already:
   https://mise.jdx.dev/getting-started.html.
2. Run `mise install` in the project root to install all required tools.
3. Still read the rest of the guide, because not all tools are managed by mise.

#### Option 2: Manual Setup

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

Create a `local.properties` in the root of the project with paths to inform
Gradle where to find the Android SDK:

```properties
# Replace the path with the actual path on your machine
sdk.dir=/Users/username/Library/Android/sdk
```

### Building for Apple platforms

Install XCode to build for Apple platforms. Mise will do this for you with
`xcodes`. If installing manually, use the version named in the
[`.xcode-version`](.xcode-version) file.

### Building for Desktop

Desktop consumes the published
[`maplibre-native-ffi`](https://github.com/maplibre/maplibre-native-ffi) Kotlin
Multiplatform bindings, so there is no C++ toolchain, CMake, or vendored
MapLibre Native checkout to set up.

The desktop tests do drive a real GPU, through a headless Vulkan device. Linux
and Windows have a system Vulkan loader; macOS does not, so `mise run bootstrap`
installs one over MoltenVK. Run it — or `brew install vulkan-loader molten-vk` —
before trusting a green desktop suite on a Mac: without a loader those tests
skip rather than fail, which looks the same in the output.

Desktop is validated on Linux, and its test suite also runs on macOS. Windows is
implemented but has not been run on real hardware yet; see
[DESKTOP_FFI_REWRITE.md](./DESKTOP_FFI_REWRITE.md).

## Run the demo

Use IntelliJ or Android Studio to launch the demo app on Android, XCode to
launch on iOS, and Gradle to launch on JS or Desktop:

- Android emulator: if the app crashes while creating a Vulkan renderer, run the
  OpenGL demo flavor instead with
  `./gradlew :demo-app:installDebug -PdemoAppMaplibreAndroidFlavor=opengl`.
- Desktop: `./gradlew :demo-app:run`
- Web: `./gradlew :demo-app:jsRun`

## Building documentation

- Build both MkDocs site and Dokka API reference: `./gradlew generateDocs`
- Build MkDocs only: `./gradlew mkdocsBuild`
- Build API docs only: `./gradlew dokkaGenerate`

## Make CI happy

A Git pre-commit hook is available to ensure that the code is formatted before
every commit. It'll be installed automatically if you use `mise`, but you can
remove it with:

```bash
hk uninstall
```

If not using the pre-commit hook, you can manually format the code using:

```bash
hk fix --all
```
