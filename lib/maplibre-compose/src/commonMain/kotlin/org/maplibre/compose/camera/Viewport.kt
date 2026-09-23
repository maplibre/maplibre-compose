package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.DpSize
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion

/**
 * One rendered transform of the map: its camera, size and visible area.
 *
 * Read a current instance from [org.maplibre.compose.map.MapState.viewport]. A new immutable
 * instance replaces it when the map has rendered a new camera or size. All properties of one
 * instance describe the same frame.
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
   * tilted.
   */
  public val visibleBounds: VisibleBounds,

  /**
   * The polygon formed by the map composable's four corners. Camera tilt makes it a trapezoid
   * instead of a rectangle.
   */
  public val visibleRegion: VisibleRegion,
)
