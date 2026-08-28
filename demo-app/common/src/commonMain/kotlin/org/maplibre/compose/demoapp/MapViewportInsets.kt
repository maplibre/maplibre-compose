package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Physical insets around the map viewport. */
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

fun PaddingValues.toMapViewportInsets(layoutDirection: LayoutDirection): MapViewportInsets =
  MapViewportInsets(
    left = calculateLeftPadding(layoutDirection),
    top = calculateTopPadding(),
    right = calculateRightPadding(layoutDirection),
    bottom = calculateBottomPadding(),
  )
