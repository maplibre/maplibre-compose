package org.maplibre.compose.location

import kotlin.time.TimeMark
import kotlinx.serialization.Serializable
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.Rotation

/**
 * Describes a user's location.
 *
 * @property position the geographic position and its horizontal accuracy.
 * @property speed the current speed of the user and its accuracy.
 * @property course the direction in which the user is travelling and its accuracy.
 * @property timestamp the point in time when this location was acquired. This uses [TimeMark]
 *   instead of e.g. [kotlin.time.Instant], to allow calculating how old a location is, even if the
 *   system clock changes.
 */
@Serializable
public data class Location(
  val position: PositionMeasurement,
  val speed: SpeedMeasurement? = null,
  val course: BearingMeasurement? = null,
  val timestamp: TimeMark,
)

@Serializable public data class PositionMeasurement(val position: Position, val accuracy: Length?)

@Serializable public data class BearingMeasurement(val bearing: Bearing, val accuracy: Rotation?)

@Serializable
public data class SpeedMeasurement(val distancePerSecond: Length, val accuracy: Length?)
