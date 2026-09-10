package org.maplibre.compose.location

import platform.CoreLocation.CLLocationManager

/**
 * Foreground location from Core Location.
 *
 * Applies the requested accuracy and minimum distance. [LocationRequest.minimumInterval] is
 * ignored. See [IosLocationPermissionRequester] for permission behavior.
 *
 * Create the provider, request permission, and close it on the main thread.
 *
 * Disabled location services report [LocationUnavailableReason.ServicesDisabled]. Denied or
 * declined permission reports [LocationUnavailableReason.PermissionDenied]. Network failures and
 * unknown locations report [LocationUnavailableReason.TemporarilyUnavailable]. Other failures
 * report [LocationUnavailableReason.UnexpectedFailure].
 */
public class IosLocationProvider
internal constructor(
  requester: IosLocationPermissionRequester,
  servicesEnabled: suspend () -> Boolean = ::locationServicesEnabled,
  createManager: () -> CLLocationManager = { CLLocationManager() },
) : LocationProvider by AppleLocationProvider(requester.delegate, servicesEnabled, createManager) {
  public constructor() : this(IosLocationPermissionRequester())
}
