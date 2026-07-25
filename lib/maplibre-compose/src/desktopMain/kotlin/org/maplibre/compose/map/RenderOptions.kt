package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

@Immutable
public actual data class RenderOptions(
  val isTileBordersEnabled: Boolean = false,
  val isTileTimestampsEnabled: Boolean = false,
  val isCollisionBoxesEnabled: Boolean = false,
  val isTileParseStatusEnabled: Boolean = false,
  val maximumFps: Int? = null,
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
}
