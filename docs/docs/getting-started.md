# Getting started

This documentation assumes you already have a Compose Multiplatform project set
up. If you haven't already, follow [the official JetBrains
documentation][compose-guide] to set up a project.

## Add the library to your app

This library is published via [Maven Central][maven], and snapshot builds of
`main` are additionally available on [GitHub Packages][gh-packages].

=== "Releases (Maven Central)"

    The latest release is **v{{ gradle.release_version }}**. In your Gradle version catalog, add:

    ```toml title="libs.versions.toml"
    [libraries]
    maplibre-compose = { module = "dev.sargunv.maplibre-compose:maplibre-compose", version = "{{ gradle.release_version }}" }
    ```

=== "Snapshots (GitHub Packages)"

    !!! warning

        The published documentation is for the latest release, and may not match the snapshot
        version. If using snapshots, always refer to the [latest source code][repo] for the most
        accurate information.

    First, follow [GitHub's guide][gh-packages-guide] for authenticating to GitHub Packages. Your
    settings.gradle.kts should have something like this:

    ```kotlin title="settings.gradle.kts"
    repositories {
      maven {
        url = uri("https://maven.pkg.github.com/maplibre/maplibre-compose")
        credentials {
          username = project.findProperty("gpr.user") as String? ?: System.getenv("GH_USERNAME")
          password = project.findProperty("gpr.key") as String? ?: System.getenv("GH_TOKEN")
        }
      }
    }
    ```

    The latest snapshot is **v{{ gradle.snapshot_version }}**. In your Gradle version catalog, add:

    ```toml title="libs.versions.toml"
    [libraries]
    maplibre-compose = { module = "dev.sargunv.maplibre-compose:maplibre-compose", version = "{{ gradle.snapshot_version }}" }
    ```

In your Gradle build script, add:

```kotlin title="build.gradle.kts"
commonMain.dependencies {
  implementation(libs.maplibre.compose)
}
```

## Set up iOS

For iOS, you'll additionally need to add the MapLibre framework to your build.
The easiest way to do this in Kotlin Multiplatform is with the [CocoaPods Gradle
plugin][kotlin-cocoapods]:

```kotlin title="build.gradle.kts"
cocoapods {
  pod("MapLibre", "{{ gradle.maplibre_ios_version }}")
}
```

## Set up Vulkan on Android (Optional)

By default, we ship with the standard version of MapLibre for Android, which
uses the OpenGL backend. If you'd prefer to use the Vulkan backend, you can
update your build.

First, add the Vulkan build of MapLibre to your version catalog:

```toml title="libs.versions.toml"
[libraries]
maplibre-android-vulkan = { module = "org.maplibre.gl:android-sdk-vulkan", version = "{{ gradle.maplibre_android_version }}" }
```

Then, exclude the standard MapLibre build from your dependency tree, and add the
Vulkan build to your Android dependencies:

```kotlin title="build.gradle.kts"
commonMain.dependencies {
  // Note the .get().toString()! This is needed to work around a limitation in the Kotlin Gradle plugin.
  implementation(libs.maplibre.compose.get().toString()) {
    exclude(group = "org.maplibre.gl", module = "android-sdk")
  }
}

androidMain.dependencies {
  implementation(libs.maplibre.android.vulkan)
}
```

## Set up Web (JS)

!!! warning

    Web support is not yet at feature parity with Android and iOS. Check the [status table](index.md#status) for more info.

For Web, you'll additionally need to add the MapLibre CSS to your page. The
easiest way to do this is via the CDN:

```html title="index.html"
<!doctype html>
<html lang="en">
  <head>
    <link
      rel="stylesheet"
      href="https://unpkg.com/maplibre-gl@{{ gradle.maplibre_js_version }}/dist/maplibre-gl.css"
    />
    <title>Example Map</title>
  </head>
</html>
```

## Set up Desktop (JVM)

!!! warning

    Desktop support is not yet at feature parity with Android and iOS. Check the [status table](index.md#status) for more info.

On desktop, we use [DATL4g/KCEF][kcef] to embed a Chromium based browser. It
requires some special configuration.

Add this Maven repo to your project:

```kotlin title="settings.gradle.kts"
repositories {
  maven("https://jogamp.org/deployment/maven")
}
```

Add these JVM flags to your app:

```kotlin title="build.gradle.kts"
compose.desktop {
  application {
    jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
    jvmArgs(
      "--add-opens",
      "java.desktop/java.awt.peer=ALL-UNNAMED"
    ) // recommended but not necessary

    if (System.getProperty("os.name").contains("Mac")) {
      jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
      jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
    }
  }
}
```

Wrap your app with `KcefProvider` to download KCEF on first lanch, and
`MaplibreContext` to provide the library with context about the window your app
is running in:

```kotlin title="Main.kt"
-8 < -"demo-app/src/desktopMain/kotlin/dev/sargunv/maplibrecompose/demoapp/Main.kt:main"
```

## Display your first map

In your Composable UI, add a map:

```kotlin title="App.kt"
-8 < -"demo-app/src/commonMain/kotlin/dev/sargunv/maplibrecompose/demoapp/docs/GettingStarted.kt:app"
```

When you run your app, you should see the default [demotiles] map. To learn how
to get a detailed map with all the features you'd expect, proceed to
[Styling](./styling.md).

[compose-guide]:
  https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-create-first-app.html
[maven]: https://central.sonatype.com/namespace/dev.sargunv.maplibre-compose
[gh-packages]:
  https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry
[gh-packages-guide]:
  https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry#using-a-published-package
[kotlin-cocoapods]: https://kotlinlang.org/docs/native-cocoapods.html
[repo]: https://github.com/maplibre/maplibre-compose
[demotiles]: https://demotiles.maplibre.org/
[kcef]: https://github.com/DatL4g/KCEF
