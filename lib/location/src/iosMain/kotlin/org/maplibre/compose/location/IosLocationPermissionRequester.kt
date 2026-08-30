package org.maplibre.compose.location

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
 * Foreground Core Location permission holder.
 *
 * [IosLocationProvider] delegates [LocationProvider.permission] and
 * [LocationProvider.requestPermission] to an instance of this class. Use it directly when a custom
 * provider needs the same Core Location permission behavior.
 *
 * [`CLAuthorizationStatus`](https://developer.apple.com/documentation/corelocation/clauthorizationstatus)
 * maps an authorized status to [LocationPermission.Granted], `notDetermined` to
 * [LocationPermission.Required] with `canRequest = true`, `denied` or `restricted` to `canRequest =
 * false`, and an unrecognized value to `canRequest = null`.
 * [`CLLocationManager.accuracyAuthorization`](https://developer.apple.com/documentation/corelocation/cllocationmanager/accuracyauthorization)
 * distinguishes precise from approximate grants.
 */
public class IosLocationPermissionRequester {
  private val mutableStatus = MutableStateFlow<LocationPermission>(LocationPermission.Unknown)

  /** Current foreground location permission, updated when Core Location reports a change. */
  public val status: StateFlow<LocationPermission> = mutableStatus
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

  /**
   * Starts a foreground permission request and returns immediately. The result is published to
   * [status].
   */
  public fun requestForegroundPermission() {
    val current = readStatus(manager)
    if (current !is LocationPermission.Required || current.canRequest != true || requestPending)
      return
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
      kCLAuthorizationStatusNotDetermined -> LocationPermission.Required(canRequest = true)
      kCLAuthorizationStatusDenied,
      kCLAuthorizationStatusRestricted -> LocationPermission.Required(canRequest = false)
      else -> LocationPermission.Unknown
    }
}
