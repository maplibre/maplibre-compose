# Location

MapLibre Compose supports two different ways to render and track user location.

## Compose pipeline

Use the Compose pipeline when you want full control over the rendered indicator
and camera behavior in Compose:

- [`LocationPuck`](api/index.html#org.maplibre.compose.location/LocationPuck)
  renders a Compose-managed puck from a `UserLocationState`.
- [`LocationTrackingEffect`](api/index.html#org.maplibre.compose.location/LocationTrackingEffect)
  updates your `CameraState` from the same source.

```kotlin
val locationState = rememberUserLocationState(rememberDefaultLocationProvider())

MaplibreMap(cameraState = cameraState) {
  LocationTrackingEffect(locationState, enabled = true) {
    cameraState.updateFromLocation()
  }

  LocationPuck(
    idPrefix = "user-location",
    locationState = locationState,
    cameraState = cameraState,
  )
}
```

## Native tracking pipeline

Use the native pipeline when your app already computes the tracked location and
you want MapLibre's native follow mode and native current-location puck to use
that exact source.

This is useful for navigation use cases where the preferred position is not the
raw platform GPS location, for example:

- on-route: snapped location
- off-route: raw location

```kotlin
val preferredLocationState = rememberUserLocationState(preferredLocationProvider)
val nativeTrackingState =
  rememberNativeLocationTrackingState(UserTrackingMode.FollowWithCourse)

MaplibreMap(
  cameraState = cameraState,
  nativeLocationTracking =
    NativeLocationTracking(
      locationState = preferredLocationState,
      state = nativeTrackingState,
      puck = NativeLocationPuck.Default,
    ),
)
```

### Behavior

- `NativeLocationTracking` is opt-in. If you do not set it, existing behavior is
  unchanged.
- `NativeLocationTrackingState.trackingMode` is bidirectional: the app can
  request a mode, and native dismissals write the current mode back.
- The native pipeline is implemented in v1 on Android and iOS.
- On Web and Desktop, `nativeLocationTracking` is currently a no-op.

### Do not combine both puck pipelines for the same source

`LocationPuck` and `NativeLocationTracking(..., puck = ...)` are alternative
rendering pipelines. Do not use both for the same location source in the same
map unless you intentionally want two indicators.

If both are active in one `MaplibreMap`, MapLibre Compose logs a warning.
