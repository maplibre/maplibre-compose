package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlin.time.Duration

@Composable
public actual fun rememberDefaultOrientationProvider(
  updateInterval: Duration
): OrientationProvider {
  return rememberIosLocationProvider(
    enableLocation = false,
    enableOrientation = true,
    orientationUpdateInterval = updateInterval,
  )
}
