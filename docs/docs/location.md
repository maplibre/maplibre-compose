# Integrating location

The location module provides a cross-platform way to track and visualize user
position in MapLibre Compose.

Location support is split into a few small pieces: providers collect position
and orientation updates, `LocationPuck` draws the user's location on the map,
and `LocationTrackingEffect` can optionally keep the camera in sync with those
updates.

### Overview

| Component                                | Purpose                                                                            |
| ---------------------------------------- | ---------------------------------------------------------------------------------- |
| **`rememberDefaultLocationProvider`**    | Provides location data from GPS or other location sources.                         |
| **`rememberDefaultOrientationProvider`** | Provides device orientation data from sensors.                                     |
| **`LocationPuck`**                       | Visual indicator that displays the user's current position and bearing on the map. |
| **`LocationTrackingEffect`**             | Composable effect to subscribe to location updates.                                |

Android and iOS provide default location and orientation providers. On Desktop
and Web there is no default: `rememberDefaultLocationProvider` and
`rememberDefaultOrientationProvider` throw, so supply a custom
`LocationProvider` or `OrientationProvider`.

### Bearing

`Location.course` is the direction of movement reported by the location
provider. `Orientation.orientation` is the direction the device is pointing. Use
`locationState.mostAccurateBearing()` when either source is acceptable and the
more accurate one should be used.

The puck bearing and camera bearing are separate: `LocationPuck(bearing = ...)`
rotates only the puck indicator, while `LocationTrackingEffect` and
`updateFromLocation(updateBearing = ...)` control camera rotation.

| Bearing update mode | Camera behavior                                          |
| ------------------- | -------------------------------------------------------- |
| `IGNORE`            | Keep the current camera bearing.                         |
| `ALWAYS_NORTH`      | Reset the camera to north.                               |
| `TRACK_COURSE`      | Rotate the camera with the user's direction of movement. |
| `TRACK_ORIENTATION` | Rotate the camera with the device orientation.           |
| `TRACK_AUTOMATIC`   | Use the more accurate course or orientation measurement. |

### Implementation

```kotlin
-8<- "demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Location.kt:puck"
```

### Customizing the puck

Use `accuracyThreshold = Float.POSITIVE_INFINITY` to hide the accuracy circle.
Use `showBearing = false` or `showBearingAccuracy = false` to hide bearing
indicators. If you use the Material 3 extension module, pass
`colors = LocationPuckDefaults.colors()` for themed colors. `onClick` and
`onLongClick` can react to interactions with the puck.

### Platform Options

#### **Android**

Request runtime location permission before collecting location updates. For
better accuracy on Android, use the Fused Location/Orientation Provider
(requires Google Play Services):

```kotlin
val locationProvider = rememberFusedLocationProvider()
val orientationProvider = rememberFusedOrientationProvider()
```

Maven dependency:

```kotlin
implementation('com.google.android.gms:play-services-location:21.3.0')
```
