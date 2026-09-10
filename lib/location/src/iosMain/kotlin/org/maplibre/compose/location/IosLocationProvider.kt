package org.maplibre.compose.location

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
public class IosLocationProvider internal constructor(requester: IosLocationPermissionRequester) :
  LocationProvider by AppleLocationProvider(requester.delegate) {
  public constructor() : this(IosLocationPermissionRequester())
}
