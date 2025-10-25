package org.maplibre.compose.demoapp

@androidx.compose.runtime.Composable
actual fun rememberLocationPermissionState(): LocationPermissionState {
  return object : LocationPermissionState {
    override val hasPermission: Boolean = false

    override fun requestPermission() {}
  }
}
