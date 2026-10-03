package org.maplibre.compose.desktop.bridge

import org.jetbrains.skia.SurfaceOrigin
import org.maplibre.compose.mlnffi.TextureOrigin

internal fun TextureOrigin.toSkiaOrigin(): SurfaceOrigin =
  when (this) {
    TextureOrigin.TopLeft -> SurfaceOrigin.TOP_LEFT
    TextureOrigin.BottomLeft -> SurfaceOrigin.BOTTOM_LEFT
  }
