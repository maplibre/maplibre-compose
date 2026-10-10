package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Position

/**
 * The camera's center, orientation, zoom, and screen-space framing.
 *
 * @param center Geographic position at the center of the camera's visible area.
 * @param zoom Zoom level at center. A value in the range of `[0 .. 25.5]`
 * @param bearing Direction that the camera is pointing in, in degrees clockwise from north.
 * @param pitch The camera angle, in degrees, from the nadir (directly down). The map keeps it
 *   within the minimum and maximum pitch of
 *   [CameraConstraints][org.maplibre.compose.map.CameraConstraints], which default to 0 and 60.
 *   Both engines accept a maximum pitch of up to 180.
 * @param padding Physical edge insets in dp, added to the presentation's viewport insets. The
 *   center appears at the center of the remaining area.
 */
@Immutable
@Serializable
@OptIn(ExperimentalSerializationApi::class)
public data class CameraPosition(
  @JsonNames("target") public val center: Position = Position(0.0, 0.0),
  public val zoom: Double = 1.0,
  public val bearing: Double = 0.0,
  public val pitch: Double = 0.0,
  public val padding: DpPadding = DpPadding.Zero,
) {
  /** Targets every property, including properties equal to the current camera value. */
  public fun toCameraUpdate(): CameraUpdate = CameraUpdate(center, zoom, bearing, pitch, padding)
}
