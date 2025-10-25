package org.maplibre.compose.camera

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.dp
import org.maplibre.spatialk.geojson.Position

/**
 * Defines how the camera is oriented towards the map.
 *
 * @param bearing Direction that the camera is pointing in, in degrees clockwise from north.
 * @param target Target position to align with the center of the screen, i.e. where the camera is
 *   pointing at.
 * @param tilt The camera angle, in degrees, from the nadir (directly down). A value in the range of
 *   `[0 .. 60]`
 * @param zoom Zoom level at target. A value in the range of `[0 .. 25.5]`
 * @param padding By default, the camera points at the center of the screen, i.e. it also rotates
 *   and tilts around that point. By specifying a padding, this center point can be altered, for
 *   example if you want to display a bottom sheet above the lower part of the map, focussing on a
 *   POI in the upper part of the map.
 */
@Stable // not @Immutable because of PaddingValues
public data class CameraPosition(
  public val bearing: Double = 0.0,
  public val target: Position = Position(0.0, 0.0),
  public val tilt: Double = 0.0,
  public val zoom: Double = 1.0,
  public val padding: PaddingValues = PaddingValues(0.dp),
)
