package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.StateFlow

public interface GeoLocator {
  public val location: StateFlow<Location?>
}

public enum class DesiredAccuracy {
  Highest,
  High,
  Balanced,
  Low,
  Lowest,
}

/**
 * Create and remember a [GeoLocator] using the default implementation for the platform.
 *
 * **NOTE:** There are also platform-specific `remember*GeoLocator` functions you may want to use.
 *
 * @param updateInterval the desired interval for updates
 * @param desiredAccuracy the [DesiredAccuracy] for location updates
 * @throws NotImplementedError if [GeoLocator] no default [GeoLocator] is provided for the platform
 */
@Composable
public expect fun rememberDefaultGeoLocator(
  updateInterval: Duration = 1.seconds,
  desiredAccuracy: DesiredAccuracy = DesiredAccuracy.Highest,
): GeoLocator
