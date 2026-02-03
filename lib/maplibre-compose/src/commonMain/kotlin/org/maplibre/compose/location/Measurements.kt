package org.maplibre.compose.location

import kotlinx.serialization.Serializable
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.Rotation

/**
 * Represents a measured geographical position with an associated accuracy.
 *
 * @property value The measured geographical position, represented as a [Position] object (latitude,
 *   longitude, and optional altitude).
 * @property accuracy The estimated accuracy of the position measurement, typically as a radius. A
 *   `null` value indicates that the accuracy is unknown.
 */
@Serializable public data class PositionMeasurement(val value: Position, val accuracy: Length?)

/**
 * Represents a bearing measurement, combining a bearing value with its potential accuracy.
 *
 * @property value The measured bearing.
 * @property accuracy The potential error in the bearing measurement, expressed as a [Rotation]. A
 *   smaller value indicates higher accuracy. A `null` value implies that the accuracy is unknown.
 */
@Serializable public data class BearingMeasurement(val value: Bearing, val accuracy: Rotation?)

/**
 * Represents a speed measurement.
 *
 * @property distancePerSecond The measured speed, expressed as a length traveled per second.
 * @property accuracy The potential error in the speed measurement, also expressed as a length per
 *   second. A smaller value indicates a more precise measurement. A `null` value indicates that the
 *   accuracy is unknown.
 */
@Serializable
public data class SpeedMeasurement(val distancePerSecond: Length, val accuracy: Length?)
