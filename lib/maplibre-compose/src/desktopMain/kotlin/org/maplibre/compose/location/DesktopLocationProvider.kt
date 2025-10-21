package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlin.time.Duration

@Composable
public actual fun rememberDefaultLocationProvider(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
  minDistanceMeters: Double,
): LocationProvider {
  throw NotImplementedError("no default implementation for desktop")
}
