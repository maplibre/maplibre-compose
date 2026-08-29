package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * @param isTileBordersEnabled Draws the boundary of every tile the map is built from.
 * @param isCollisionBoxesEnabled Draws the boxes symbol placement uses to decide what to hide.
 * @param isPaddingEnabled Draws the camera's padding, which is where it considers its centre to be.
 * @param isOverdrawInspectorEnabled Shades the map by how many times each pixel was drawn.
 * @param maximumFps Caps how often the map is rendered. Null renders whenever MapLibre asks to be.
 */
@Immutable
public actual data class RenderOptions(
  val isTileBordersEnabled: Boolean = false,
  val isCollisionBoxesEnabled: Boolean = false,
  val isPaddingEnabled: Boolean = false,
  val isOverdrawInspectorEnabled: Boolean = false,
  val maximumFps: Int? = null,
) {
  public actual companion object Companion {
    public actual val Standard: RenderOptions = RenderOptions()
    public actual val Debug: RenderOptions =
      RenderOptions(
        isTileBordersEnabled = true,
        isCollisionBoxesEnabled = true,
        isPaddingEnabled = true,
      )
  }
}
