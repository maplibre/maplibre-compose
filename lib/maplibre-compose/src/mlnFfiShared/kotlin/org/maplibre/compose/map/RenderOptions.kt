package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * @param isTileBordersEnabled Draws the boundary of every tile the map is built from.
 * @param isTileTimestampsEnabled Draws the time each tile was last updated.
 * @param isCollisionBoxesEnabled Draws the boxes symbol placement uses to decide what to hide.
 * @param isTileParseStatusEnabled Draws tile parse state on each tile.
 * @param maximumFps Caps how often the map is rendered. Null renders whenever MapLibre asks to be.
 * @param renderMode How Android presents the map. Desktop ignores this value.
 */
@Immutable
public actual data class RenderOptions(
  val isTileBordersEnabled: Boolean = false,
  val isTileTimestampsEnabled: Boolean = false,
  val isCollisionBoxesEnabled: Boolean = false,
  val isTileParseStatusEnabled: Boolean = false,
  val maximumFps: Int? = null,
  val renderMode: RenderMode = RenderMode.TextureView,
) {
  public actual companion object Companion {
    public actual val Standard: RenderOptions = RenderOptions()
    public actual val Debug: RenderOptions =
      RenderOptions(
        isTileBordersEnabled = true,
        isTileTimestampsEnabled = true,
        isCollisionBoxesEnabled = true,
        isTileParseStatusEnabled = true,
      )
  }

  /** How Android presents MapLibre's OpenGL output. Desktop ignores this value. */
  public enum class RenderMode {
    /** A TextureView. Overlays and transforms composite with the rest of the Compose hierarchy. */
    TextureView,

    /**
     * A SurfaceView. Often cheaper to present, and sits above Compose content in this Android host.
     */
    SurfaceView,
  }
}
