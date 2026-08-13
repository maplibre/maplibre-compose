package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.CoreLocation.CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.darwin.NSObject

/**
 * Foreground Core Location permission requester.
 *
 * [`CLAuthorizationStatus`](https://developer.apple.com/documentation/corelocation/clauthorizationstatus)
 * maps an authorized status to [LocationPermission.Granted], `notDetermined` to
 * [LocationPermission.NotGranted] with `canRequest = true`, `denied` or `restricted` to `canRequest
 * = false`, and an unrecognized value to `canRequest = null`.
 * [`CLLocationManager.accuracyAuthorization`](https://developer.apple.com/documentation/corelocation/cllocationmanager/accuracyauthorization)
 * distinguishes precise from approximate grants.
 */
public class IosLocationPermissionRequester : LocationPermissionRequester {
  private val mutableStatus =
    MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(canRequest = null))
  override val status: StateFlow<LocationPermission> = mutableStatus
  private var requestPending = false

  private val delegate =
    object : NSObject(), CLLocationManagerDelegateProtocol {
      override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        val value = readStatus(manager)
        mutableStatus.value = value
        requestPending = false
      }
    }

  private val manager = CLLocationManager().also { it.delegate = delegate }

  init {
    mutableStatus.value = readStatus(manager)
  }

  override fun requestForegroundPermission() {
    val current = readStatus(manager)
    if (current != LocationPermission.NotGranted(canRequest = true) || requestPending) return
    requestPending = true
    manager.requestWhenInUseAuthorization()
  }

  private fun readStatus(manager: CLLocationManager): LocationPermission =
    when (manager.authorizationStatus) {
      kCLAuthorizationStatusAuthorizedAlways,
      kCLAuthorizationStatusAuthorizedWhenInUse ->
        LocationPermission.Granted(
          if (manager.accuracyAuthorization == CLAccuracyAuthorizationFullAccuracy) {
            LocationAccuracyAuthorization.Precise
          } else {
            LocationAccuracyAuthorization.Approximate
          }
        )
      kCLAuthorizationStatusNotDetermined -> LocationPermission.NotGranted(canRequest = true)
      kCLAuthorizationStatusDenied,
      kCLAuthorizationStatusRestricted -> LocationPermission.NotGranted(canRequest = false)
      else -> LocationPermission.NotGranted(canRequest = null)
    }
}

@Composable
public fun rememberIosLocationPermissionRequester(): IosLocationPermissionRequester = remember {
  IosLocationPermissionRequester()
}

@Composable
public actual fun rememberDefaultLocationPermissionRequester(): LocationPermissionRequester =
  rememberIosLocationPermissionRequester()
