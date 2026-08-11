package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
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
 * Foreground Core Location permission controller.
 *
 * [`CLAuthorizationStatus`](https://developer.apple.com/documentation/corelocation/clauthorizationstatus)
 * maps an authorized status to [LocationPermission.Granted], `notDetermined` to
 * [LocationPermission.NotGranted] with `canRequest = true`, `denied` or `restricted` to `canRequest
 * = false`, and an unrecognized value to `canRequest = null`.
 * [`CLLocationManager.accuracyAuthorization`](https://developer.apple.com/documentation/corelocation/cllocationmanager/accuracyauthorization)
 * distinguishes precise from approximate grants.
 */
public class IosLocationPermissionController : LocationPermissionController {
  private val mutableStatus =
    MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(canRequest = null))
  override val status: StateFlow<LocationPermission> = mutableStatus
  private var pendingRequest: CompletableDeferred<LocationPermission>? = null

  private val delegate =
    object : NSObject(), CLLocationManagerDelegateProtocol {
      override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        val value = readStatus(manager)
        mutableStatus.value = value
        pendingRequest?.complete(value)
        pendingRequest = null
      }
    }

  private val manager = CLLocationManager().also { it.delegate = delegate }

  init {
    mutableStatus.value = readStatus(manager)
  }

  override suspend fun requestForegroundPermission(): LocationPermission =
    withContext(Dispatchers.Main) {
      val current = readStatus(manager)
      if (current != LocationPermission.NotGranted(canRequest = true)) return@withContext current
      pendingRequest?.let {
        return@withContext it.await()
      }

      val result = CompletableDeferred<LocationPermission>()
      pendingRequest = result
      manager.requestWhenInUseAuthorization()
      result.await()
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
public fun rememberIosLocationPermissionController(): IosLocationPermissionController = remember {
  IosLocationPermissionController()
}
