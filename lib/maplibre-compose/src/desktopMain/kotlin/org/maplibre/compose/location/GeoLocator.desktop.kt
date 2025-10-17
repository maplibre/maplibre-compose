package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlin.time.Duration

@Composable
public actual fun rememberDefaultGeoLocator(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
  minDistanceMeters: Double,
): GeoLocator {
  throw NotImplementedError("no default implementation for desktop")
}
