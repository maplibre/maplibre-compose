package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * @param maximumFps The maximum frame rate at which the map view is rendered. It can't exceed the
 *   ability of device hardware.
 * @param debugSettings Options for enabling debugging features.
 */
@Immutable
public actual data class RenderOptions(
  public val maximumFps: Int? = null,
  public val debugSettings: DebugSettings = DebugSettings(),
) {
  public actual companion object Companion {
    public actual val Standard: RenderOptions = RenderOptions()
    public actual val Debug: RenderOptions =
      RenderOptions(
        debugSettings =
          DebugSettings(
            isTileBoundariesEnabled = true,
            isTileInfoEnabled = true,
            isTimestampsEnabled = true,
            isCollisionBoxesEnabled = true,
          )
      )
  }

  /**
   * @param isTileBoundariesEnabled Indicates if tile boundaries should be displayed as thick, red
   *   lines. Helps diagnose tile clipping issues.
   * @param isTileInfoEnabled Indicates if each tile should show its tile coordinate (x/y/z) in the
   *   upper-left corner.
   * @param isTimestampsEnabled Indicates if each tile should display a timestamp denoting when it
   *   was loaded.
   * @param isCollisionBoxesEnabled Indicates if edges of glyphs and symbols should be displayed as
   *   faint, green lines. Helps diagnose collision and label placement issues.
   * @param isOverdrawVisualizationEnabled Indicates if a translucent fill should replace each
   *   drawing operation, making overlapping drawing operations more prominent to diagnose
   *   overdrawing.
   */
  @Immutable
  public data class DebugSettings(
    val isTileBoundariesEnabled: Boolean = false,
    val isTileInfoEnabled: Boolean = false,
    val isTimestampsEnabled: Boolean = false,
    val isCollisionBoxesEnabled: Boolean = false,
    val isOverdrawVisualizationEnabled: Boolean = false,
  )
}
