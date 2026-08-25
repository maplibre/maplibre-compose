package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Four-sided padding in device-independent pixels.
 *
 * Style-spec padding uses physical sides: left, top, right, and bottom. A negative value shrinks
 * that side of the box.
 */
@Immutable
public data class DpPadding(
  val left: Dp = 0.dp,
  val top: Dp = 0.dp,
  val right: Dp = 0.dp,
  val bottom: Dp = 0.dp,
) {
  public companion object {
    public val Zero: DpPadding = DpPadding()
  }
}

/** Copies the four physical sides of [this] into a [DpPadding]. */
public fun PaddingValues.Absolute.toDpPadding(): DpPadding =
  DpPadding(
    left = calculateLeftPadding(LayoutDirection.Ltr),
    top = calculateTopPadding(),
    right = calculateRightPadding(LayoutDirection.Ltr),
    bottom = calculateBottomPadding(),
  )
