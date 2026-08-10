package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlin.time.Duration
import org.maplibre.spatialk.units.Length

/**
 * The browser has a Geolocation API, but wiring it to [LocationProvider] is its own piece of work —
 * permissions, watch lifetimes, and accuracy all behave differently there — and it is tracked
 * separately from the platform reboot. Supply a [LocationProvider] of your own until then.
 */
@Composable
public actual fun rememberDefaultLocationProvider(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
  minDistance: Length,
): LocationProvider {
  throw NotImplementedError("no default location provider for the browser")
}

@Composable
public actual fun rememberDefaultOrientationProvider(
  updateInterval: Duration
): OrientationProvider {
  throw NotImplementedError("no default orientation provider for the browser")
}
