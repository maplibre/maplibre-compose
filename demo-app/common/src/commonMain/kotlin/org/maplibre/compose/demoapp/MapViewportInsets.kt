package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Physical insets around the part of the map that the app can use. */
@Immutable
data class MapViewportInsets(
  val left: Dp = 0.dp,
  val top: Dp = 0.dp,
  val right: Dp = 0.dp,
  val bottom: Dp = 0.dp,
) {
  fun union(other: MapViewportInsets): MapViewportInsets =
    MapViewportInsets(
      left = maxOf(left, other.left),
      top = maxOf(top, other.top),
      right = maxOf(right, other.right),
      bottom = maxOf(bottom, other.bottom),
    )

  fun asPaddingValues(): PaddingValues.Absolute =
    PaddingValues.Absolute(left = left, top = top, right = right, bottom = bottom)

  fun asWindowInsets(): WindowInsets =
    WindowInsets(left = left, top = top, right = right, bottom = bottom)

  companion object {
    val Zero = MapViewportInsets()
  }
}

fun WindowInsets.toMapViewportInsets(
  density: Density,
  layoutDirection: LayoutDirection,
): MapViewportInsets =
  with(density) {
    MapViewportInsets(
      left = getLeft(density, layoutDirection).toDp(),
      top = getTop(density).toDp(),
      right = getRight(density, layoutDirection).toDp(),
      bottom = getBottom(density).toDp(),
    )
  }

internal fun maximumSheetHeight(
  viewportHeight: Dp,
  topSafeInset: Dp,
  minimumUsefulHeight: Dp,
): Dp =
  minOf(
    (viewportHeight - topSafeInset).coerceAtLeast(0.dp),
    maxOf(viewportHeight / 2, minimumUsefulHeight),
  )

internal fun visibleSheetHeight(viewportHeightPx: Int, sheetOffsetPx: Float): Int =
  (viewportHeightPx - sheetOffsetPx).toInt().coerceIn(0, viewportHeightPx)
