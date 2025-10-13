package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlin.time.Duration

@Composable
public actual fun rememberDefaultGeoLocator(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
): GeoLocator {
  throw NotImplementedError("no default implementation for desktop")
}
