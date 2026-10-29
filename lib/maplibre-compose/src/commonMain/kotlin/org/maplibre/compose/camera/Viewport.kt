package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.Position

/**
 * One rendered frame of the map: its camera, size, visible area, and the conversions between
 * geographic positions and screen offsets in it.
 *
 * Read the current instance from [org.maplibre.compose.map.MapState.viewport], which is null until
 * the map renders its first frame. A new immutable instance replaces it when the map renders a new
 * camera or size. Every property and method of one instance answers for the frame it describes,
 * even after the camera has moved on, so read `viewport` again for the latest frame.
 */
@Immutable
public data class Viewport
internal constructor(
  /** The camera this viewport was rendered with. */
  public val cameraPosition: CameraPosition,

  /** The size of the map composable this viewport was rendered at. */
  public val size: DpSize,

  /**
   * The smallest bounds that contain the currently visible area.
   *
   * These north-aligned bounds can include areas outside [visibleRegion] when the map is rotated or
   * pitched.
   */
  public val visibleBounds: VisibleBounds,

  /**
   * The polygon formed by the map composable's four corners. Camera pitch makes it a trapezoid
   * instead of a rectangle.
   */
  public val visibleRegion: VisibleRegion,

  /** Meters per dp at the camera's center position. */
  public val metersPerDpAtCenter: Double,
) {
  // Outside the constructor, so equality, copy, and componentN ignore it. A copy describes a
  // different frame, so it starts without one.
  internal var projection: ViewportProjection? = null
    private set

  internal fun withProjection(projection: ViewportProjection?): Viewport = also {
    this.projection = projection
  }

  /**
   * Projects [position] into a logical-pixel offset from the top left of the map, as this
   * viewport's frame drew it, even after the camera has moved on.
   *
   * Longitudes equivalent modulo 360° project onto the world copy nearest the camera center.
   * Returns null if the map did not draw this viewport's frame.
   */
  public fun screenLocationFromPosition(position: Position): DpOffset? =
    projection?.screenLocationFromPosition(position)

  /**
   * Unprojects a logical-pixel [offset] from the top left of the map into a geographic position, as
   * this viewport's frame drew it, even after the camera has moved on.
   *
   * Longitude preserves the visible world copy and may extend past ±180°. Returns null if the map
   * did not draw this viewport's frame.
   */
  public fun positionFromScreenLocation(offset: DpOffset): Position? =
    projection?.positionFromScreenLocation(offset)

  /**
   * The ground distance that one dp covers at [latitude] in this viewport's frame. Returns null if
   * the map did not draw this viewport's frame.
   */
  public fun metersPerDpAtLatitude(latitude: Double): Double? =
    projection?.metersPerDpAtLatitude(latitude)
}

/** The projection of one frame's transform, independent of later camera changes. */
internal interface ViewportProjection {
  fun screenLocationFromPosition(position: Position): DpOffset

  fun positionFromScreenLocation(offset: DpOffset): Position

  fun metersPerDpAtLatitude(latitude: Double): Double
}
