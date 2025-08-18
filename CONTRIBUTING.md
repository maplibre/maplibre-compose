# Contributing

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

Create a `local.properties` in the root of the project with paths to inform
Gradle where to find the Android SDK:

```properties
# Replace the path with the actual path on your machine
sdk.dir=/Users/username/Library/Android/sdk
```

As there's no stable LSP for Kotlin Multiplatform, you'll want to use either
IntelliJ IDEA or Android Studio for developing MapLibre Compose. In addition to
the IDE, you'll need some plugins:

- [Kotlin Multiplatform](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform)
- [Android](https://plugins.jetbrains.com/plugin/22989-android)
- [Android Design Tools](https://plugins.jetbrains.com/plugin/22990-android-design-tools)
- [Jetpack Compose](https://plugins.jetbrains.com/plugin/18409-jetpack-compose)
- [Native Debugging Support](https://plugins.jetbrains.com/plugin/12775-native-debugging-support)

If developing on a Mac, install XCode to build for Apple platforms. Jetbrains
[has a table](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-compatibility-guide.html#version-compatibility)
of supported XCode versions by Kotlin version. Check the compatibility table and
install the latest supported XCode version. I recommend using
[Xcodes](https://www.xcodes.app/) to manage multiple XCode versions.

If you have any trouble; check out
[the official instructions](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-setup.html)
for setting up a Kotlin Multiplaform environment.

## Run the demo

Use IntelliJ or Android Studio to launch the demo app on Android, XCode to
launch on iOS, and Gradle to launch on JS or Desktop:

- Desktop: `./gradlew :demo-app:run`
- Web: `./gradlew :demo-app:jsRun`

## Make CI happy

A Git pre-commit hook is available to ensure that the code is formatted before
every commit. Install hooks with [pre-commit](https://pre-commit.com/):

```bash
pre-commit install
```

Uninstall with:

```bash
pre-commit uninstall
```
