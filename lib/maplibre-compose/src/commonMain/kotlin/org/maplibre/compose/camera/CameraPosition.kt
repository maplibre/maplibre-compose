package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Position

/**
 * The camera's target, orientation, zoom, and screen-space framing.
 *
 * @param bearing Direction that the camera is pointing in, in degrees clockwise from north.
 * @param target Position that the camera points at.
 * @param tilt The camera angle, in degrees, from the nadir (directly down). A value in the range of
 *   `[0 .. 60]`
 * @param zoom Zoom level at target. A value in the range of `[0 .. 25.5]`
 * @param padding Physical edge insets in dp, added to the presentation's viewport insets. The
 *   target appears at the center of the remaining area.
 */
@Immutable
@Serializable
public data class CameraPosition(
  public val bearing: Double = 0.0,
  public val target: Position = Position(0.0, 0.0),
  public val tilt: Double = 0.0,
  public val zoom: Double = 1.0,
  public val padding: DpPadding = DpPadding.Zero,
)
