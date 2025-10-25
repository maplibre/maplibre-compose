package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable

@Composable
actual fun rememberLocationPermissionState(): LocationPermissionState {
  return object : LocationPermissionState {
    override val hasPermission: Boolean = false

    override fun requestPermission() {}
  }
}
