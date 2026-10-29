package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.Position

/**
 * One transform of the map: its camera, size, visible area, and the conversions between geographic
 * positions and screen offsets in it.
 *
 * Read the current instance from [org.maplibre.compose.map.MapState.viewport], which is null until
 * the map is presented at a nonzero size. The map publishes a new immutable instance when it
 * applies a new camera or size, and draws that transform in its next frame, so the screen can lag
 * the newest instance by a frame. Every property and method of one instance answers for the
 * transform it describes, even after the camera has moved on, so read `viewport` again for the
 * latest one. One exception: in the browser, on a map with terrain, the conversions between
 * positions and screen offsets read the live elevation data, so their results can change as
 * elevation tiles load.
 */
@Immutable
public data class Viewport
internal constructor(
  /** The camera of this transform. */
  public val cameraPosition: CameraPosition,

  /** The size of the map composable in this transform. */
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
  // different transform, so it starts without one.
  internal var projection: ViewportProjection? = null
    private set

  internal fun withProjection(projection: ViewportProjection?): Viewport = also {
    this.projection = projection
  }

  /**
   * Projects [position] into a logical-pixel offset from the top left of the map, as this
   * viewport's transform places it, even after the camera has moved on.
   *
   * Longitudes equivalent modulo 360° project onto the world copy nearest the camera center.
   * Returns null for a viewport that did not come from the map.
   */
  public fun screenLocationFromPosition(position: Position): DpOffset? =
    projection?.screenLocationFromPosition(position)

  /**
   * Unprojects a logical-pixel [offset] from the top left of the map into a geographic position, as
   * this viewport's transform places it, even after the camera has moved on.
   *
   * Longitude preserves the visible world copy and may extend past ±180°. Returns null for a
   * viewport that did not come from the map.
   */
  public fun positionFromScreenLocation(offset: DpOffset): Position? =
    projection?.positionFromScreenLocation(offset)

  /**
   * The ground distance that one dp covers at [latitude] in this viewport's transform. Returns null
   * for a viewport that did not come from the map.
   */
  public fun metersPerDpAtLatitude(latitude: Double): Double? =
    projection?.metersPerDpAtLatitude(latitude)
}

/** The projection of one transform, independent of later camera changes. */
internal interface ViewportProjection {
  fun screenLocationFromPosition(position: Position): DpOffset

  fun positionFromScreenLocation(offset: DpOffset): Position

  fun metersPerDpAtLatitude(latitude: Double): Double
}
