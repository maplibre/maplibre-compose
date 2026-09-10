package org.maplibre.compose.location

import kotlinx.coroutines.flow.StateFlow
import platform.CoreLocation.CLLocationManager

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
  public constructor() : this(CLLocationManager())

  internal val delegate = AppleLocationPermissionRequester(manager)
  public val status: StateFlow<LocationPermission>
    get() = delegate.status

  public fun requestForegroundPermission(): Unit = delegate.requestForegroundPermission()

  override fun close(): Unit = delegate.close()
}
