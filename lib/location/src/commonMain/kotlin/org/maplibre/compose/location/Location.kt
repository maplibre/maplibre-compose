package org.maplibre.compose.location

import kotlin.time.TimeMark
import kotlinx.serialization.Serializable
import org.maplibre.spatialk.units.Length

/**
 * Describes a user's location.
 *
 * @property position the geographic position and its horizontal accuracy.
 * @property altitudeAccuracy the accuracy of [PositionWithAccuracy.value]'s altitude, or `null` if
 *   the source does not report it.
 * @property speed the current speed of the user and its accuracy.
 * @property course the direction in which the user is travelling and its accuracy.
 * @property timestamp the point in time when this location was acquired. This uses [TimeMark]
 *   instead of e.g. [kotlin.time.Instant], to allow calculating how old a location is, even if the
 *   system clock changes.
 */
@Serializable
public data class Location(
  val position: PositionWithAccuracy,
  val altitudeAccuracy: Length? = null,
  val speed: SpeedWithAccuracy? = null,
  val course: BearingWithAccuracy? = null,
  val timestamp: TimeMark,
)
