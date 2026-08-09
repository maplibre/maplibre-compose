package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@ExperimentalPermissionsApi
private class AndroidLocationPermissionState(private val permissionState: PermissionState) :
  LocationPermissionState {
  override val hasPermission: Boolean
    get() = permissionState.status.isGranted

  override fun requestPermission() {
    permissionState.launchPermissionRequest()
  }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
actual fun rememberLocationPermissionState(): LocationPermissionState {
  val permissionState = rememberPermissionState(android.Manifest.permission.ACCESS_FINE_LOCATION) {}
  return remember(permissionState) { AndroidLocationPermissionState(permissionState) }
}
