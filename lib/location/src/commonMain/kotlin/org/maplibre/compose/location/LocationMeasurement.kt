package org.maplibre.compose.location

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.Rotation

/**
 * One measured geographic location.
 *
 * @property position Geographic position, with an optional altitude in meters.
 * @property horizontalAccuracy Estimated horizontal error radius, or `null` when unknown.
 * @property altitudeAccuracy Estimated altitude error, or `null` when unknown or when [position]
 *   contains no altitude.
 * @property distancePerSecond Distance that corresponds to one second at the reported speed, or
 *   `null` when the source reports no speed.
 * @property distancePerSecondAccuracy Estimated error in [distancePerSecond], or `null` when the
 *   error is unknown.
 * @property course Bearing in the direction of travel, or `null` when the source reports no course.
 * @property courseAccuracy Estimated course error, or `null` when unknown.
 * @property measuredAt Wall-clock instant when the location was measured.
 */
@Serializable
public data class LocationMeasurement(
  val position: Position,
  val horizontalAccuracy: Length? = null,
  val altitudeAccuracy: Length? = null,
  val distancePerSecond: Length? = null,
  val distancePerSecondAccuracy: Length? = null,
  val course: Bearing? = null,
  val courseAccuracy: Rotation? = null,
  val measuredAt: Instant,
) {
  init {
    require(position.altitude != null || altitudeAccuracy == null) {
      "altitudeAccuracy requires position altitude"
    }
    require(distancePerSecond != null || distancePerSecondAccuracy == null) {
      "distancePerSecondAccuracy requires distancePerSecond"
    }
    require(course != null || courseAccuracy == null) { "courseAccuracy requires course" }
  }
}
