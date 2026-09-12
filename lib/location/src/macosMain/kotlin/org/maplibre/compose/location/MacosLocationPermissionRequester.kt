package org.maplibre.compose.location

import kotlinx.coroutines.flow.StateFlow
import platform.CoreLocation.CLLocationManager

/** Foreground Core Location permission. Construct, request, and close on the main thread. */
public class MacosLocationPermissionRequester internal constructor(manager: CLLocationManager) :
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
