package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.darwin.NSObject

private class IosLocationPermissionState : LocationPermissionState {
  private val delegate: CLLocationManagerDelegateProtocol =
    object : NSObject(), CLLocationManagerDelegateProtocol {
      override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        authorizationStatus = manager.authorizationStatus
      }
    }

  private val locationManager = CLLocationManager().also { it.delegate = delegate }

  private var authorizationStatus by mutableStateOf(locationManager.authorizationStatus)

  override val hasPermission: Boolean
    get() =
      authorizationStatus == kCLAuthorizationStatusAuthorizedWhenInUse ||
        authorizationStatus == kCLAuthorizationStatusAuthorizedAlways

  override fun requestPermission() {
    locationManager.requestWhenInUseAuthorization()
  }
}

@Composable
actual fun rememberLocationPermissionState(): LocationPermissionState {
  return remember { IosLocationPermissionState() }
}
