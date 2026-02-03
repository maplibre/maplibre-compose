package org.maplibre.compose.location

import kotlin.time.TimeMark
import kotlinx.serialization.Serializable

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
