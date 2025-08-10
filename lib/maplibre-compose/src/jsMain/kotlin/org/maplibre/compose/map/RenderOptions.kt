package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/** @param debugSettings Options for enabling debugging features. */
@Immutable
public actual data class RenderOptions(val debugSettings: DebugSettings = DebugSettings()) {
  public actual companion object Companion {
    public actual val Standard: RenderOptions = RenderOptions()
    public actual val Debug: RenderOptions =
      RenderOptions(
        debugSettings =
          DebugSettings(showCollisionBoxes = true, showTileBoundaries = true, showPadding = true)
      )
  }

  /**
   * @param showCollisionBoxes Set whether the map will render boxes around all symbols in the data
   *   source, revealing which symbols were rendered or which were hidden due to collisions.
   * @param showTileBoundaries Set whether the map will render an outline around each tile and the
   *   tile ID. The uncompressed file size of the first vector source is drawn in the top left
   *   corner of each tile, next to the tile ID.
   * @param showPadding Set whether the map will visualize the padding offsets.
   * @param showOverdrawInspector Set whether the map should color-code each fragment to show how
   *   many times it has been shaded. White fragments have been shaded 8 or more times. Black
   *   fragments have been shaded 0 times.
   */
  @Immutable
  public data class DebugSettings(
    val showCollisionBoxes: Boolean = false,
    val showTileBoundaries: Boolean = false,
    val showPadding: Boolean = false,
    val showOverdrawInspector: Boolean = false,
  )
}
