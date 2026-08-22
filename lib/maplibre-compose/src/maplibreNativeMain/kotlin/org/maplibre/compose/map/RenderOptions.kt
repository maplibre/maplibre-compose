package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * @param isTileBordersEnabled Draws the boundary of every tile the map is built from.
 * @param isTileTimestampsEnabled Draws the time each tile was last updated.
 * @param isCollisionBoxesEnabled Draws the boxes symbol placement uses to decide what to hide.
 * @param isTileParseStatusEnabled Draws tile parse state on each tile.
 * @param maximumFps Caps how often the map is rendered. Null uses the display refresh rate.
 * @param preferredRenderMode How Android presents the map. iOS and desktop ignore this value.
 * @param foregroundLoadColor The color shown in place of the map until the first style has loaded.
 *   Transparent leaves the content behind the map visible.
 */
@Immutable
public actual data class RenderOptions(
  val isTileBordersEnabled: Boolean = false,
  val isTileTimestampsEnabled: Boolean = false,
  val isCollisionBoxesEnabled: Boolean = false,
  val isTileParseStatusEnabled: Boolean = false,
  val maximumFps: Int? = null,
  val preferredRenderMode: RenderMode = RenderMode.Surface,
  val foregroundLoadColor: Color = Color.Transparent,
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
   * How Android presents the map. iOS and desktop ignore this value.
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
