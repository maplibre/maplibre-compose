package org.maplibre.compose.location.desktop.macos

import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.DesktopLocationPermissionRequester
import org.maplibre.compose.location.DesktopLocationProvider

/** Scaffold for the macOS desktop location backend. */
public class MacosLocationBackend : DesktopLocationBackend {
  override val id: String = "core-location"

  // TODO: Implement location with CLLocationManager: requestWhenInUseAuthorization,
  // startUpdatingLocation, stopUpdatingLocation, and the authorization, location, and error
  // delegate callbacks. Map CLLocation fields and timestamps to the common API. Packages must
  // declare NSLocationWhenInUseUsageDescription and the location entitlement.
  //
  // Implement orientation with headingAvailable, startUpdatingHeading, stopUpdatingHeading, and
  // locationManager:didUpdateHeading:. Prefer trueHeading when valid, otherwise magneticHeading;
  // map headingAccuracy and the CLHeading timestamp to the common API.
  override fun isAvailable(): Boolean = false

  override fun createProvider(host: ComposeMapHost?): DesktopLocationProvider =
    error("The macOS location backend is not implemented")

  override fun createPermissionRequester(
    host: ComposeMapHost?
  ): DesktopLocationPermissionRequester = error("The macOS location backend is not implemented")
}
