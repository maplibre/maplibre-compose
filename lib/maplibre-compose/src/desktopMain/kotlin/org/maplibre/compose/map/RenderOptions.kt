package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import org.maplibre.kmp.native.map.MapDebugOptions

@Immutable
public actual data class RenderOptions(
  val debugOptions: MapDebugOptions = MapDebugOptions(),
  val maximumFps: Int? = null,
) {
  public actual companion object Companion {
    public actual val Standard: RenderOptions = RenderOptions()
    public actual val Debug: RenderOptions =
      RenderOptions(
        debugOptions =
          MapDebugOptions(
            tileBorders = true,
            timestamps = true,
            collision = true,
            parseStatus = true,
          )
      )
  }
}
