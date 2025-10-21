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

/**
 * Accuracy levels for [rememberDefaultGeoLocator], which will be mapped to platform accuracy and
 * power levels.
 *
 * The recommended level for actively displaying the user location on screen is [High] or
 * [Balanced], if the location doesn't have to be perfectly accurate.
 */
public enum class DesiredAccuracy {
  /**
   * Request highest possible accuracy and accept high power usage
   *
   * On Android this is equivalent to [High]; on iOS it corresponds to "best for navigation apps".
   * Prefer [High] unless you know you need [Highest].
   */
  Highest,

  /**
   * Request very high accuracy and accept high power usage
   *
   * On Android this requests the highest possible level of accuracy (same as [Highest]). On iOS
   * this requests very high accuracy, suitable for everything but real-time navigation.
   */
  High,

  /**
   * Request a balance of accuracy and power usage.
   *
   * On Android this lets the platform optimize power usage while still requesting reasonably
   * accurate locations. On iOS this corresponds to an accuracy of roughly 100 m.
   */
  Balanced,

  /**
   * Request a rough location with low power usage.
   *
   * On Android this is the lowest level that still results in an active location requests, and
   * therefore the lowest level that guarantees regular location updates. The updates will, however,
   * be optimized for low power usage and not for accuracy. On iOS this corresponds to an accuracy
   * of roughly 1 km.
   */
  Low,

  /**
   * Request any location with the lowest possible power usage without regard for accuracy.
   *
   * On Android this corresponds to the passive provider, i.e. there won't be an active request for
   * location updates. Instead the app will only receive updates, when another app also requests
   * location updates. On iOS this corresponds to the reduced accuracy level that is also available
   * when the app does not have permission for precise locations.
   */
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
  desiredAccuracy: DesiredAccuracy = DesiredAccuracy.High,
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
