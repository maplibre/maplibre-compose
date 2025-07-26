package org.maplibre.compose.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection

@Composable
internal fun WindowInsets.afterConsuming(other: WindowInsets): WindowInsets {
  val density = LocalDensity.current
  val layoutDir = LocalLayoutDirection.current
  return WindowInsets(
    maxOf(0, getLeft(density, layoutDir) - other.getLeft(density, layoutDir)),
    maxOf(0, getTop(density) - other.getTop(density)),
    maxOf(0, getRight(density, layoutDir) - other.getRight(density, layoutDir)),
    maxOf(0, getBottom(density) - other.getBottom(density)),
  )
}
