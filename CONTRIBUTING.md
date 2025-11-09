# Contributing

## Clone the repo with submodules

```bash
git clone --recurse-submodules --shallow-submodules https://github.com/maplibre/maplibre-compose.git
```

Or if you already have the repo cloned, run:

```bash
git submodule update --init --recursive --depth=1
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

For desktop, we build a C++ library that includes
[MapLibre Native Core](https://maplibre.org/maplibre-native/docs/book/introduction.html).
You'll need to have your developer environment set up to build MapLibre Native.

- [macOS requirements](https://maplibre.org/maplibre-native/docs/book/platforms/macos/index.html)
  - Install XCode, and use the matching clang version provided by XCode rather
    than from homebrew. `/usr/bin/clang --version` and `clang --version` should
    match.
  - If building the Vulkan backend, set the `VULKAN_SDK` environment variable to
    the MoltenVK prefix (`export VULKAN_SDK="$(brew --prefix molten-vk)"`).
- [Linux requirements](https://maplibre.org/maplibre-native/docs/book/platforms/linux/index.html#requirements)
  - On Fedora, install the following:
    ```bash
    sudo dnf group install c-development development-tools
    sudo dnf install cmake ninja-build clang \
      libcurl-devel libjpeg-turbo-devel libpng-devel libwebp-devel \
      libX11-devel mesa-libGL-devel libuv-devel bzip2-devel libicu-devel \
      vulkan-loader-devel
    ```
  - On Ubuntu, install the following:
    ```bash
    sudo apt update
    sudo apt install build-essential cmake ninja-build clang \
      libcurl4-openssl-dev libjpeg-turbo8-dev libpng-dev libwebp-dev \
      libx11-dev libgl1-mesa-dev libuv1-dev libbz2-dev libicu-dev \
      libvulkan-dev
    ```
- [Windows requirements (MSVS2022)](https://maplibre.org/maplibre-native/docs/book/platforms/windows/build-msvc.html#prerequisites)
  - When cloning the repo, pass `--config core.longpaths=true` to Git to avoid
    issues with long file paths.
  - Install Visual Studio 2022 with the following workloads:
    - `Desktop development with C++`
  - Use the _Native Tools Command Prompt for VS 2022_ for the right architecture
    (x64 or arm64 depending on your machine) to run the build scripts.
  - Developing MapLibre Compose with MSYS2 has not been tested.

## Run the demo

Use IntelliJ or Android Studio to launch the demo app on Android, XCode to
launch on iOS, and Gradle to launch on JS or Desktop:

- Desktop: `./gradlew :demo-app:run`
- Web: `./gradlew :demo-app:jsRun`

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
