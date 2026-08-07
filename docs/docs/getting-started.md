# Getting started

This documentation assumes you already have a Compose Multiplatform project set
up. If you haven't already, follow
[the official JetBrains documentation][compose-guide] to set up a project.

## Add the library to your app

This library is published via [Maven Central][maven], and snapshot builds of
`main` are additionally available from [Central Portal Snapshots][snapshots].

=== "Releases (Maven Central)"

    The latest release is **v{{ gradle.release_version }}**. In your Gradle version catalog, add:

    ```toml title="libs.versions.toml"
    [libraries]
    maplibre-compose = { module = "org.maplibre.compose:maplibre-compose", version = "{{ gradle.release_version }}" }
    ```

=== "Snapshots (Central Portal)"

    !!! warning

        The published documentation is for the latest release, and may not match the snapshot
        version. If using snapshots, always refer to the [latest source code][repo] for the most
        accurate information.

    Add the Central Portal Snapshots repository to your `settings.gradle.kts`:

    ```kotlin title="settings.gradle.kts"
    repositories {
      maven {
        name = "Central Portal Snapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
        content {
          includeGroup("org.maplibre.compose")
          includeGroup("org.maplibre.nativeffi")
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

!!! warning

    In Xcode, ensure your Kotlin/Compose framework is linked before
    `MapLibre.framework`. Select your iOS app target, open **Build Settings**,
    search for **Other Linker Flags**, and order the flags like this:

    ```text
    -framework ComposeApp
    -framework MapLibre
    ```

    Replace `ComposeApp` with your Kotlin framework name. The opposite order can
    cause broken Compose text rendering on iOS because both Compose and
    MapLibre include HarfBuzz symbols.

    See [CMP-8882](https://youtrack.jetbrains.com/issue/CMP-8882/)

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
kotlin {
  listOf(
    iosX64(),
    iosArm64(),
    iosSimulatorArm64()
  ).forEach { target ->
    target.swiftPackageConfig {
      dependency {
        remotePackageVersion(
          url = URI("https://github.com/maplibre/maplibre-gl-native-distribution.git"),
          products = { add("MapLibre", exportToKotlin = true) },
          packageName = "maplibre-gl-native-distribution",
          version = "{{ gradle.maplibre_ios_version }}",
        )
      }
    }

    target.binaries.framework {
      baseName = "ComposeApp"
      isStatic = true
    }
  }
}
```

## Revert to OpenGL on Android (Optional)

!!! warning

    The OpenGL renderer is available for compatibility, but Vulkan is the default
    renderer for MapLibre Android 13 and later.
    Some Android emulators do not expose Vulkan support; use OpenGL when Vulkan
    initialization fails in an emulator.

By default, we ship with the standard version of MapLibre for Android, which
uses the Vulkan backend. If you'd prefer to use the OpenGL backend, you can
update your build.

First, add the OpenGL build of MapLibre to your version catalog:

```toml title="libs.versions.toml"
[libraries]
maplibre-android-opengl = { module = "org.maplibre.gl:android-sdk-opengl", version = "{{ gradle.maplibre_android_version }}" }
```

Then, exclude the standard MapLibre build from your dependency tree, and add the
OpenGL build to your Android dependencies:

```kotlin title="build.gradle.kts"
commonMain.dependencies {
  implementation(libs.maplibre.compose.get().toString()) { // (1)!
    exclude(group = "org.maplibre.gl", module = "android-sdk")
  }
}

androidMain.dependencies {
  implementation(libs.maplibre.android.opengl)
}
```

1. The `.get().toString()` is needed to work around a limitation in the Kotlin
   Gradle plugin.

## Set up Web (JS)

!!! warning

    Web support is not yet at feature parity with Android and iOS. Check the [status table](index.md#status) for more info.

There are no longer any special steps required to use MapLibre Compose on Web.

## Set up Desktop (JVM)

Alongside the library, add a runtime: the native libraries for one platform and
one render backend.

```kotlin title="build.gradle.kts"
sourceSets {
  val desktopMain by getting {
    dependencies {
      implementation(compose.desktop.currentOs)
      implementation("org.maplibre.compose:maplibre-compose:{{ gradle.release_version }}")

      // Linux x64, for example.
      runtimeOnly(
        "org.maplibre.compose:maplibre-compose-runtime-vulkan-linux-x64:" +
          "{{ gradle.release_version }}"
      )
    }
  }
}
```

Provide each AWT window so MapLibre uses that window's GPU context:

```kotlin title="Main.kt"
fun main() = singleWindowApplication {
  ProvideMapHost(
    host = rememberAwtComposeGpuHost(window),
    runtimeOptions =
      DesktopRuntimeOptions(cachePath = desktopCachePath("com.example.myapp")),
  ) {
    App()
  }
}
```

Available runtimes:

| Platform      | Runtime                                         |
| ------------- | ----------------------------------------------- |
| Linux x64     | `maplibre-compose-runtime-vulkan-linux-x64`     |
| Linux arm64   | `maplibre-compose-runtime-vulkan-linux-arm64`   |
| macOS arm64   | `maplibre-compose-runtime-metal-macos-arm64`    |
| Windows x64   | `maplibre-compose-runtime-vulkan-windows-x64`   |
| Windows arm64 | `maplibre-compose-runtime-vulkan-windows-arm64` |

To ship several platforms, select the runtime from the host you build on.

**Desktop requires Java 25.** The MapLibre Native FFI binding uses the FFM API,
so the desktop target cannot run on an older JVM.

**The JVM needs native access.** MapLibre Native FFI makes FFM downcall. If you
package your application with Compose Desktop's `nativeDistributions`, add the
argument to your application configuration:

```kotlin title="build.gradle.kts"
compose.desktop {
  application {
    jvmArgs += "--enable-native-access=ALL-UNNAMED"
  }
}
```

If you launch an unpackaged JVM application instead — `java -jar`, an IDE run
configuration, or a `JavaExec` task — pass the same argument on the command
line:

```bash
java --enable-native-access=ALL-UNNAMED -jar your-app.jar
```

## Display your first map

In your Composable UI, add a map:

```kotlin title="App.kt"
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/GettingStarted.kt:app"
```

!!! warning

    Make sure you're importing `org.maplibre.compose.map.MaplibreMap` instead of `org.maplibre.android.map.MaplibreMap`.

When you run your app, you should see the default [demotiles] map. To learn how
to get a detailed map with all the features you'd expect, proceed to
[Styling](./styling.md).

[compose-guide]: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-create-first-app.html
[maven]: https://central.sonatype.com/namespace/org.maplibre.compose
[snapshots]: https://central.sonatype.com/repository/maven-snapshots/org/maplibre/compose/
[gradle-cocoapods]: https://kotlinlang.org/docs/native-cocoapods.html
[gradle-spm4kmp]: https://spmforkmp.eu/
[cocoapods-support]: https://blog.cocoapods.org/CocoaPods-Support-Plans/
[repo]: https://github.com/maplibre/maplibre-compose
[demotiles]: https://demotiles.maplibre.org/
