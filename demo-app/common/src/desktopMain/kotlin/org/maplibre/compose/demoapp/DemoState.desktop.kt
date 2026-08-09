package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable

private object FakeLocationPermissionState : LocationPermissionState {
  override val hasPermission: Boolean = false

  override fun requestPermission() {}
}

@Composable
actual fun rememberLocationPermissionState(): LocationPermissionState {
  return FakeLocationPermissionState
}
