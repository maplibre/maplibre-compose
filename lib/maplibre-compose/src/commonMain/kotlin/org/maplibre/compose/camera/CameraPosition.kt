package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import org.maplibre.spatialk.geojson.Position

/**
 * Defines how the camera is oriented towards the map.
 *
 * @param bearing Direction that the camera is pointing in, in degrees clockwise from north.
 * @param target Position that the camera points at. The world repeats horizontally, so target
 *   longitudes equivalent modulo 360° point at the same map. MapLibre Native wraps an out-of-range
 *   longitude into ±180° when the camera is applied and read back; the browser keeps the value as
 *   given.
 * @param tilt The camera angle, in degrees, from the nadir (directly down). A value in the range of
 *   `[0 .. 60]`
 * @param zoom Zoom level at target. A value in the range of `[0 .. 25.5]`
 */
@Immutable
public data class CameraPosition(
  public val bearing: Double = 0.0,
  public val target: Position = Position(0.0, 0.0),
  public val tilt: Double = 0.0,
  public val zoom: Double = 1.0,
)
