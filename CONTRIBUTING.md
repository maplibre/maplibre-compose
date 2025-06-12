# Contributing

## Development

Create a `local.properties` in the root of the project with paths to inform
Gradle where to find the Android SDK:

```properties
# Replace the paths with the actual paths on your machine
sdk.dir=/Users/username/Library/Android/sdk
```

Follow
[Jetbrains's instructions](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-setup.html#get-help)
for setting up an environment for KMP development.

Once set up, if you're using [Fleet](https://www.jetbrains.com/fleet/), you can
use the [preconfigured run configurations](./.fleet/run.json) in this repo to
launch the demo app or run tests.

If you're not using Fleet, use Android Studio to launch the demo app on Android,
XCode to launch on iOS, and Gradle to run the tests:

- Android emulator tests: `./gradlew connectedDebugAndroidTest`
- iOS simulator tests: `./gradlew iosSimulatorArm64Test`

A Git pre-commit hook is available to ensure that the code is formatted before
every commit. Run `./gradlew installGitHooks` to install it. It will run
`./gradlew spotlessApply` before every commit. To run the formatter, you'll need
to have `swift` installed (for `swift format`) and `npm` installed (for
`prettier`). Or run just
`./gradlew spotlessKotlinApply spotlessKotlinGradleApply` to skip those
dependencies.
