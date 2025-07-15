# Interacting with the map

## Gestures and ornaments

Configuraton options for interaction vary significantly by platform. We provide
some presets in common code, but if you're working with multiple platforms and
you want to configure these in detail, you'll need to use expect/actual code.

```kotlin title="map.common.kt"
-8<- "demo-app/src/androidMain/kotlin/org/maplibre/compose/docsnippets/Interaction.kt:common-gesture-ornament"
```

### Gestures

The map supports pan, zoom, rotate, and tilt gestures. Each of these can be
enabled or disabled individually.

```kotlin title="map.android.kt"
-8<- "demo-app/src/androidMain/kotlin/org/maplibre/compose/docsnippets/Interaction.kt:gesture-settings"
```

### Ornaments

!!! info

    We provide Material 3 alternatives to the default ornaments. See the [Material 3 extensions
    section](./material3.md) for more information.

Ornaments are built in UI elements that are displayed on the map, such as a
compass or attribution button. You can control the visibility and position of
these ornaments. They're implemented by the underlying MapLibre SDK, so may
render differently on different platforms and the available options vary by
platform.

```kotlin title="map.android.kt"
-8<- "demo-app/src/androidMain/kotlin/org/maplibre/compose/docsnippets/Interaction.kt:ornament-settings"
```

1. Insets the ornaments; useful if you have an edge-to-edge map or some UI
   elements that cover part of the map.
2. Displays a MapLibre logo
3. Possible alignments are constrained by the underlying SDK. The four corners
   are supported across platforms.
4. Displays attribution defined in the map style.
5. Displays a compass control when the map is rotated away from north.
6. Displays a scale control showing the distance represented by the map's zoom
   level.

## Camera

If you want to read or mutate the camera state, use `rememberCameraState()`. You
can use this to set the start position of the map:

```kotlin
-8<- "demo-app/src/androidMain/kotlin/org/maplibre/compose/docsnippets/Interaction.kt:camera"
```

You can now use the `camera` reference to move the camera. For example,
`CameraState` exposes a `suspend fun animateTo` to animate the camera to a new
position:

```kotlin
-8<- "demo-app/src/androidMain/kotlin/org/maplibre/compose/docsnippets/Interaction.kt:camera-animate"
```

## Click listeners

You can listen for clicks on the map. Given a click location, you can use camera
state to query which features are present at that location:

```kotlin
-8<- "demo-app/src/androidMain/kotlin/org/maplibre/compose/docsnippets/Interaction.kt:click-listeners"
```

1. Consumes the click event, preventing it from propagating to the individual
   layers' click listeners.
