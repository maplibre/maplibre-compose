package org.maplibre.compose.location

/**
 * A request for a location update. This class is used to configure the location provider.
 *
 * @param position True if the position should be included in the location update, false otherwise.
 *   Defaults to true.
 * @param speed True if the speed should be included in the location update, false otherwise.
 *   Defaults to true.
 * @param course True if the course should be included in the location update, false otherwise.
 *   Defaults to true.
 * @param orientation True if the orientation should be included in the location update, false
 *   otherwise. Defaults to false.
 */
public data class LocationRequest(
  val position: Boolean = true,
  val speed: Boolean = true,
  val course: Boolean = true,
  val orientation: Boolean = false,
)
