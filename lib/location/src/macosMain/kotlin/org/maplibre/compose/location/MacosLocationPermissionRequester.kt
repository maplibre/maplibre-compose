package org.maplibre.compose.location

import kotlinx.coroutines.flow.StateFlow
import platform.CoreLocation.CLLocationManager

/** Foreground Core Location permission. Construct, request, and close on the main thread. */
public class MacosLocationPermissionRequester internal constructor(manager: CLLocationManager) :
  AutoCloseable {
  public constructor() : this(CLLocationManager())

  internal val delegate = AppleLocationPermissionRequester(manager)
  public val status: StateFlow<LocationPermission>
    get() = delegate.status

  public fun requestForegroundPermission(): Unit = delegate.requestForegroundPermission()

  override fun close(): Unit = delegate.close()
}
