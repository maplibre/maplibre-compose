package org.maplibre.compose.map.internal

import org.maplibre.compose.map.RenderOptions

internal fun RenderOptions.validate() {
  val maximumFps = maximumFps
  require(maximumFps == null || maximumFps > 0) {
    "maximumFps must be positive, was $maximumFps"
  }
}
