package org.maplibre.compose.demoapp.demos

import org.maplibre.compose.map.RenderOptions

actual val LensRenderOptionsDefault: RenderOptions =
  RenderOptions.Standard.copy(preferredRenderMode = RenderOptions.RenderMode.Texture)
