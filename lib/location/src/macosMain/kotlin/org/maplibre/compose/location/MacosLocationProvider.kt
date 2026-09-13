package org.maplibre.compose.location

/**
 * Foreground Core Location updates. Construct, request permission, and close on the main thread.
 * Applies accuracy and minimum distance; ignores [LocationRequest.minimumInterval]. Cancel
 * collectors before closing. The application must declare its location usage description.
 */
public class MacosLocationProvider
internal constructor(requester: MacosLocationPermissionRequester) :
  LocationProvider by AppleLocationProvider(requester.delegate) {
  public constructor() : this(MacosLocationPermissionRequester())
}
