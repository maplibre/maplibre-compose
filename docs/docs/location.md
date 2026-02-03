# 📍 Location

The location module provides a cross-platform way to track and visualize user
position in MapLibre Compose.

### Overview

| Component                                | Purpose                                                                            |
| ---------------------------------------- | ---------------------------------------------------------------------------------- |
| **`rememberDefaultLocationProvider`**    | Provides location data from GPS or other location sources.                         |
| **`rememberDefaultOrientationProvider`** | Provides device orientation data from sensors.                                     |
| **`LocationPuck`**                       | Visual indicator that displays the user's current position and bearing on the map. |
| **`LocationTrackingEffect`**             | Composable effect to subscribe to location updates.                                |

### Implementation

```kotlin
-8 < -"demo-app/src/commonMain/kotlin/org/maplibre/compose/docsnippets/Location.kt:puck"
```

### Platform Options

#### **Android**

For better accuracy on Android, use the Fused Location/Orientaton Provider
(requires Google Play Services):

```kotlin
val locationProvider = rememberFusedLocationProvider()
val orientationProvider = rememberFusedOrientationProvider()
```

Maven dependency:

```kotlin
implementation('com.google.android.gms:play-services-location:21.3.0')
```
