package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * @param isTileBordersEnabled Draws the boundary of every tile the map is built from.
 * @param isTileTimestampsEnabled Draws the time each tile was last updated.
 * @param isCollisionBoxesEnabled Draws the boxes symbol placement uses to decide what to hide.
 * @param isTileParseStatusEnabled Draws tile parse state on each tile.
 * @param maximumFps Caps how often the map is rendered. Null uses the display refresh rate. Android
 *   also asks SurfaceFlinger for this cadence.
 * @param preferredRenderMode A hint for how the host presents the map. A platform may ignore a
 *   value it does not support.
 */
@Immutable
public actual data class RenderOptions(
  val isTileBordersEnabled: Boolean = false,
  val isTileTimestampsEnabled: Boolean = false,
  val isCollisionBoxesEnabled: Boolean = false,
  val isTileParseStatusEnabled: Boolean = false,
  val maximumFps: Int? = null,
  val preferredRenderMode: RenderMode = RenderMode.Surface,
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

  /**
   * A hint for how the host presents the map. A platform may ignore a value it does not support.
   *
   * [Surface] is preferred for performance. A SurfaceView is a separate window layer, so some
   * Compose modifiers do not apply to it. Use [Texture] when a modifier such as alpha must affect
   * the map.
   */
  public enum class RenderMode {
    /** A TextureView on Android. Compose modifiers apply to the map as they do to other content. */
    Texture,

    /**
     * A SurfaceView on Android. Preferred for performance. The surface sits behind the window, so
     * Compose overlays draw on top of the map.
     */
    Surface,
  }
}
