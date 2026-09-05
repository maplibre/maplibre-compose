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
import platform.Foundation.NSThread
import platform.darwin.NSObject

/**
 * Foreground Core Location permission holder.
 *
 * [IosLocationProvider] delegates [LocationProvider.permission] and
 * [LocationProvider.requestPermission] to an instance of this class. Use it directly when a custom
 * provider needs the same Core Location permission behavior.
 *
 * Construct, request permission, and [close] on the main thread. Closing detaches the permission
 * observer and releases the manager.
 *
 * [`CLAuthorizationStatus`](https://developer.apple.com/documentation/corelocation/clauthorizationstatus)
 * maps an authorized status to [LocationPermission.Granted], `notDetermined` to
 * [LocationPermission.NotGranted] with `canRequest = true`, `denied` or `restricted` to `canRequest
 * = false`, and an unrecognized value to `canRequest = null`.
 * [`CLLocationManager.accuracyAuthorization`](https://developer.apple.com/documentation/corelocation/cllocationmanager/accuracyauthorization)
 * distinguishes precise from approximate grants.
 */
public class IosLocationPermissionRequester internal constructor(manager: CLLocationManager) :
  AutoCloseable {
  /** Creates a requester with its own Core Location manager. */
  public constructor() : this(CLLocationManager())

  private var manager: CLLocationManager? = manager
  private val mutableStatus =
    MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(canRequest = null))

  /** Current foreground location permission, updated when Core Location reports a change. */
  public val status: StateFlow<LocationPermission> = mutableStatus
  private var requestPending = false

  private val delegate =
    object : NSObject(), CLLocationManagerDelegateProtocol {
      override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        if (manager !== this@IosLocationPermissionRequester.manager) return
        val value = readStatus(manager)
        mutableStatus.value = value
        requestPending = false
      }
    }

  init {
    check(NSThread.isMainThread) { "Location permission requester requires the main thread" }
    manager.delegate = delegate
    mutableStatus.value = readStatus(manager)
  }

  /** Detaches the permission observer and releases the manager. Repeated calls have no effect. */
  override fun close() {
    check(NSThread.isMainThread) { "Location permission requester requires the main thread" }
    val manager = manager ?: return
    this.manager = null
    manager.delegate = null
    requestPending = false
  }

  /**
   * Starts a foreground permission request and returns immediately. The result is published to
   * [status].
   *
   * Throws `IllegalStateException` if the requester is closed or called off the main thread.
   */
  public fun requestForegroundPermission() {
    check(NSThread.isMainThread) { "Location permission requester requires the main thread" }
    val manager = checkNotNull(manager) { "Location permission requester is closed" }
    val current = readStatus(manager)
    if (current !is LocationPermission.NotGranted || current.canRequest != true || requestPending)
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
      kCLAuthorizationStatusNotDetermined -> LocationPermission.NotGranted(canRequest = true)
      kCLAuthorizationStatusDenied,
      kCLAuthorizationStatusRestricted -> LocationPermission.NotGranted(canRequest = false)
      else -> LocationPermission.NotGranted(canRequest = null)
    }
}
