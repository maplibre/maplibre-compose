package org.maplibre.compose.location

import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.TimeMark

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
 * @property speed the current speed of the user in meters per second
 * @property speedAccuracy the accuracy of [speed], i.e. the true speed is within +/-
 *   [speedAccuracy] m/s of [speed]
 * @property timestamp the point in time when this location was acquired. This uses [TimeMark]
 *   instead of e.g. [kotlin.time.Instant], to allow calculating how old a location is, even if the
 *   system clock changes.
 */
public data class Location(
  val position: Position,
  val accuracy: Double,
  val bearing: Double?,
  val bearingAccuracy: Double?,
  val speed: Double?,
  val speedAccuracy: Double?,
  val timestamp: TimeMark,
)
