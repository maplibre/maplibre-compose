package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlin.time.Duration
import org.maplibre.spatialk.units.Length

@Composable
public actual fun rememberDefaultLocationProvider(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
  minDistance: Length,
): LocationProvider {
  throw NotImplementedError("no default implementation for web")
}
