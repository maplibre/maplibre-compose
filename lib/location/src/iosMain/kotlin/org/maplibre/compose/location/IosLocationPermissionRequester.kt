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
  /** Creates a requester with its own Core Location manager. */
  public constructor() : this(CLLocationManager())

  internal val delegate = AppleLocationPermissionRequester(manager)

  /** Current foreground permission, updated when Core Location reports a change. */
  public val status: StateFlow<LocationPermission>
    get() = delegate.status

  /** Starts a foreground permission request. Throws if closed or called off the main thread. */
  public fun requestForegroundPermission(): Unit = delegate.requestForegroundPermission()

  /** Detaches the permission observer. Repeated calls have no effect. */
  override fun close(): Unit = delegate.close()
}
