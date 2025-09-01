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
    maplibre-compose = { module = "org.maplibre.compose:maplibre-compose", version = "{{ gradle.release_version }}" }
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
    maplibre-compose = { module = "org.maplibre.compose:maplibre-compose", version = "{{ gradle.snapshot_version }}" }
    ```

In your Gradle build script, add:

```kotlin title="build.gradle.kts"
commonMain.dependencies {
  implementation(libs.maplibre.compose)
}
```

## Set up iOS

For iOS, you'll additionally need to add the MapLibre framework to your build.
The easiest way is to select one of these two Gradle plugins:

- JetBrains's [CocoaPods plugin][gradle-cocoapods]
- Third party [Swift Package Manager plugin][gradle-spm4kmp]

### Cocoapods

!!! info

    CocoaPods will stop receiving new versions of packages in late 2026. See the [official announcement][cocoapods-support].

Follow the [official setup documentation][gradle-cocoapods], and add the below
to include MapLibre in your build:

```kotlin title="build.gradle.kts"
cocoapods {
  pod("MapLibre", "{{ gradle.maplibre_ios_version }}")
}
```

### Swift Package Manager

!!! info

    The [MapLibre Compose repository][repo] uses this plugin for development, so a working example of this configuration
    can be found there.

Follow the [official setup documentation][gradle-spm4kmp], and add the below to
include MapLibre in your build:

```kotlin title="build.gradle.kts"
swiftPackageConfig {
  create("[cinteropName]") { // (1)!
    dependency {
      remotePackageVersion(
        url = URI("https://github.com/maplibre/maplibre-gl-native-distribution.git"),
        products = { add("MapLibre") },
        version = "{{ gradle.maplibre_ios_version }}",
      )
    }
  }
}
```

1. This name must match with `cinterops.create` name.

## Set up Vulkan on Android (Optional)

!!! warning

    The Vulkan renderer is not yet as stable as the OpenGL renderer. Check the [MapLibre Native issues](https://github.com/maplibre/maplibre-native/issues?q=sort%3Aupdated-desc%20state%3Aopen%20label%3A%22Vulkan%22%20type%3ABug) for more info.

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
  implementation(libs.maplibre.compose.get().toString()) { // (1)!
    exclude(group = "org.maplibre.gl", module = "android-sdk")
  }
}

androidMain.dependencies {
  implementation(libs.maplibre.android.vulkan)
}
```

1. The `.get().toString()` is needed to work around a limitation in the Kotlin
   Gradle plugin.

## Set up Web (JS)

!!! warning

    Web support is not yet at feature parity with Android and iOS. Check the [status table](index.md#status) for more info.

For Web, you'll additionally need to add the MapLibre CSS to your page. The
easiest way to do this is via the CDN:

```html title="index.html"
<!DOCTYPE html>
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

On desktop, we use MapLibre Native via a JNI bindings module that bundles
platform-specific native libraries. Add a runtime-only dependency for each
platform you want to support, selecting exactly one renderer per OS/architecture
combination via capabilities.

```kotlin title="build.gradle.kts"
sourceSets {
  val desktopMain by getting {
    dependencies {
      implementation(compose.desktop.currentOs)
      implementation("org.maplibre.compose:maplibre-compose:{{ gradle.release_version }}")
      runtimeOnly("org.maplibre.compose:maplibre-native-bindings-jni:{{ gradle.release_version }}") {
        capabilities {
          requireCapability("org.maplibre.compose:maplibre-native-bindings-jni-macos-aarch64-metal")
          requireCapability("org.maplibre.compose:maplibre-native-bindings-jni-linux-amd64-opengl")
          requireCapability("org.maplibre.compose:maplibre-native-bindings-jni-windows-amd64-opengl")
        }
      }
    }
  }
}
```

The following targets are available now:

- `macos-aarch64-metal`
- `linux-amd64-opengl`
- `windows-amd64-opengl`

Other architectures and renderers will be added later.

## Display your first map

In your Composable UI, add a map:

```kotlin title="App.kt"
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/GettingStarted.kt:app"
```

When you run your app, you should see the default [demotiles] map. To learn how
to get a detailed map with all the features you'd expect, proceed to
[Styling](./styling.md).

[compose-guide]:
  https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-create-first-app.html
[maven]: https://central.sonatype.com/namespace/org.maplibre.compose
[gh-packages]:
  https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry
[gh-packages-guide]:
  https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry#using-a-published-package
[gradle-cocoapods]: https://kotlinlang.org/docs/native-cocoapods.html
[gradle-spm4kmp]: https://frankois944.github.io/spm4Kmp/
[cocoapods-support]: https://blog.cocoapods.org/CocoaPods-Support-Plans/
[repo]: https://github.com/maplibre/maplibre-compose
[demotiles]: https://demotiles.maplibre.org/
