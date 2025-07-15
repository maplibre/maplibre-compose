# Material 3 extensions

!!! warning

    While this module is available across all platforms, you won't be able to use it on top of the
    map on Web or Desktop due to technical limitations in Compose Multiplatform. See YouTrack issue
    [CMP-6001](https://youtrack.jetbrains.com/issue/CMP-6001) and
    [CMP-6856](https://youtrack.jetbrains.com/issue/CMP-6856).

    Android and iOS are not affected by this limitation.

## Getting Started

We provide reimplementations of certain ornaments using Material 3. These are
regular Compose UI components, so you can position them arbitrarily, style them,
animate them, etc.

To get started, add the dependency to your project:

```toml title="libs.versions.toml"
[libraries]
maplibre-composeMaterial3 = { module = "org.maplibre.compose:maplibre-compose-material3", version = "{{ gradle.release_version }}" }
```

```kotlin title="build.gradle.kts"
commonMain.dependencies {
  implementation(libs.maplibre.composeMaterial3)
}
```

Then, disable the default ornaments and add the Material controls after the
`MaplibreMap` in a shared `Box`.

```kotlin title="App.kt"
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Material3.kt:controls"
```

There are also disappearing versions of the controls which appear when relevant
and fade out after a certain time:

```kotlin title="App.kt"
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Material3.kt:disappearing-controls"
```

1. Appears when the zoom level changes; fades away when zoom is idle.
2. Appears when the map is rotated or tilted away from north and straight down;
   fades away when the map orientation is reset.
