package org.maplibre.compose.location

import kotlin.time.TimeMark
import kotlinx.serialization.Serializable
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.Rotation

/**
 * Describes a user's location
 *
 * @property position the geographic [Position] of the user
 * @property accuracy the accuracy of [position] in meters, i.e. the true location is within
 *   [accuracy] meters of [position]
 * @property bearing the bearing of the user, i.e. which direction the user is facing/travelling, in
 *   degrees east of true north, i.e. 0° being north, 90° being east, etc.
 * @property bearingAccuracy the accuracy of [bearing], i.e. the true bearing is within +/-
 *   [bearingAccuracy] degrees of [bearing]
 * @property heading The heading of the user, i.e., which direction the user's device is pointing,
 *   in degrees east of true north. For example, 0° is north, 90° is east.
 * @property headingAccuracy The accuracy of [heading], i.e., the true heading is within +/-
 *   [headingAccuracy] degrees of [heading].
 * @property speed the current speed of the user in meters per second
 * @property speedAccuracy the accuracy of [speed], i.e. the true speed is within +/-
 *   [speedAccuracy] m/s of [speed]
 * @property timestamp the point in time when this location was acquired. This uses [TimeMark]
 *   instead of e.g. [kotlin.time.Instant], to allow calculating how old a location is, even if the
 *   system clock changes.
 */
@Serializable
public data class Location(
  val position: PositionMeasurement? = null,
  val speed: SpeedMeasurement? = null,
  val course: BearingMeasurement? = null,
  val orientation: BearingMeasurement? = null,
  val timestamp: TimeMark,
)

@Serializable public data class PositionMeasurement(val position: Position, val inaccuracy: Length?)

@Serializable public data class BearingMeasurement(val bearing: Bearing, val inaccuracy: Rotation?)

@Serializable
public data class SpeedMeasurement(val distancePerSecond: Length, val inaccuracy: Length?)
