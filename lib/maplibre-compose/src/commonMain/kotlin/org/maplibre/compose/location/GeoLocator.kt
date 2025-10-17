package org.maplibre.compose.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
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

public class NullGeoLocator : GeoLocator {
  public override val location: StateFlow<Location?> = MutableStateFlow(null)
}

public class PermissionException : Exception()

/**
 * Create and remember a [GeoLocator] using the default implementation for the platform.
 *
 * The configuration parameters [updateInterval], [desiredAccuracy] and [minDistanceMeters] may
 * **all** be ignored, if the platform doesn't support them
 *
 * **NOTE:** There are also platform-specific `remember*GeoLocator` functions you may want to use,
 * if you need more control over the configuration.
 *
 * @param updateInterval the desired interval for updates
 * @param desiredAccuracy the [DesiredAccuracy] for location updates
 * @param minDistanceMeters the minimum distance between locations to trigger an update
 * @throws NotImplementedError if [GeoLocator] no default [GeoLocator] is provided for the platform
 * @throws PermissionException if the necessary platform permissions have not been granted, use
 *   [rememberNullGeoLocator] as a fallback
 * @see rememberNullGeoLocator
 */
@Composable
public expect fun rememberDefaultGeoLocator(
  updateInterval: Duration = 1.seconds,
  desiredAccuracy: DesiredAccuracy = DesiredAccuracy.Highest,
  minDistanceMeters: Double = 1.0,
): GeoLocator

/**
 * Create and remember a [GeoLocator] that never provides a location.
 *
 * This can be used as a fallback, when the user has not granted the necessary permissions.
 *
 * @see rememberDefaultGeoLocator
 */
@Composable
public fun rememberNullGeoLocator(): GeoLocator {
  return remember { NullGeoLocator() }
}
