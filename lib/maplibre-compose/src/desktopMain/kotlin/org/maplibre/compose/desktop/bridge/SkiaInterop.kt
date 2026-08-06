package org.maplibre.compose.desktop.bridge

import org.jetbrains.skia.SurfaceOrigin
import org.maplibre.compose.desktop.TextureOrigin

internal fun TextureOrigin.toSkiaOrigin(): SurfaceOrigin =
  when (this) {
    TextureOrigin.TOP_LEFT -> SurfaceOrigin.TOP_LEFT
    TextureOrigin.BOTTOM_LEFT -> SurfaceOrigin.BOTTOM_LEFT
  }
