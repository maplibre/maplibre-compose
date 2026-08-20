package org.maplibre.compose.location.desktop.windows

import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.DesktopLocationProvider

/** Scaffold for the Windows desktop location backend. */
public class WindowsLocationBackend : DesktopLocationBackend {
  override val id: String = "windows-geolocation"

  // TODO: Implement permission with AppCapability.Create("location").CheckAccess() and
  // Geolocator.RequestAccessAsync(). Implement location with Geolocator PositionChanged and
  // StatusChanged, map request preferences to Geolocator settings and local filtering, and map
  // Geoposition fields and timestamps to the common API.
  //
  // Implement orientation with Windows.Devices.Sensors.Compass.GetDefault(), ReportInterval, and
  // ReadingChanged. Prefer HeadingTrueNorth when present, otherwise HeadingMagneticNorth; reject
  // Unreliable readings and map the timestamp to the common API.
  override fun isAvailable(): Boolean = false

  override fun createProvider(host: ComposeMapHost?): DesktopLocationProvider =
    error("The Windows location backend is not implemented")
}
