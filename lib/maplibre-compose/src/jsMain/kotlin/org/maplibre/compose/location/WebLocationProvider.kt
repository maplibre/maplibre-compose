package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlin.time.Duration

@Composable
public actual fun rememberDefaultLocationProvider(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
  minDistanceMeters: Double,
  enableHeading: Boolean,
): LocationProvider {
  throw NotImplementedError("no default implementation for web")
}
