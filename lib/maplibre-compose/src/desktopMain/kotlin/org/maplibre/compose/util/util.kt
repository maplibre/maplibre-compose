package org.maplibre.compose.util

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

internal fun Alignment.toControlPosition(layoutDir: LayoutDirection): String {
  val (x, y) = align(IntSize(1, 1), IntSize(2, 2), layoutDir)
  val h =
    when (x) {
      0 -> "left"
      1 -> "right"
      else -> error("Invalid alignment")
    }
  val v =
    when (y) {
      0 -> "top"
      1 -> "bottom"
      else -> error("Invalid alignment")
    }
  return "$v-$h"
}
