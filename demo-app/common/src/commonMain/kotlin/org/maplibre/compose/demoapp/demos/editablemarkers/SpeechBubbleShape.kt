/*
 * Copyright (c) Tobias Zwick
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Adapted from StreetComplete's SpeechBubbleShape (preview omitted).
 * https://github.com/maplibre/maplibre-compose/issues/821#issuecomment-5417982234
 * See composeResources/files/SpeechBubbleShape.LICENSE for the license terms.
 */
package org.maplibre.compose.demoapp.demos.editablemarkers

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.LayoutDirection.Ltr
import androidx.compose.ui.unit.LayoutDirection.Rtl
import androidx.compose.ui.unit.dp

internal enum class SpeechBubbleArrowDirection {
  Start,
  Top,
  End,
  Bottom,
}

private enum class SpeechBubbleArrowAbsoluteDirection {
  Left,
  Top,
  Right,
  Bottom,
}

/**
 * A shape in the shape of a speech bubble, with rounded corners of radius [cornerRadius] and an
 * arrow of size [arrowSize] placed on the outline into the given [arrowDirection].
 * [arrowPlacementBias] controls where on that side the arrow is placed: 0 is start/top, 1 is
 * end/bottom.
 */
@Immutable
internal data class SpeechBubbleShape(
  private val cornerRadius: Dp = 16.dp,
  private val arrowSize: Dp = 12.dp,
  private val arrowDirection: SpeechBubbleArrowDirection = SpeechBubbleArrowDirection.Bottom,
  private val arrowPlacementBias: Float = 0f,
) : Shape {

  /**
   * Content padding for content to appear inside the speech bubble. I.e., the padding caused by the
   * speech bubble arrow.
   */
  val contentPadding: PaddingValues by lazy {
    PaddingValues(
      start = if (arrowDirection == SpeechBubbleArrowDirection.Start) arrowSize else 0.dp,
      top = if (arrowDirection == SpeechBubbleArrowDirection.Top) arrowSize else 0.dp,
      end = if (arrowDirection == SpeechBubbleArrowDirection.End) arrowSize else 0.dp,
      bottom = if (arrowDirection == SpeechBubbleArrowDirection.Bottom) arrowSize else 0.dp,
    )
  }

  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline {
    val cornerPx = with(density) { cornerRadius.toPx() }.coerceIn(0f, size.minDimension / 2f)
    // arrow can only be as big as the gap left between the rounded corners
    val maxArrowWidthPx =
      when (arrowDirection) {
        SpeechBubbleArrowDirection.Start,
        SpeechBubbleArrowDirection.End -> size.height
        SpeechBubbleArrowDirection.Top,
        SpeechBubbleArrowDirection.Bottom -> size.width
      } - cornerPx * 2f
    val arrowHeightPx = with(density) { arrowSize.toPx() }.coerceIn(0f, maxArrowWidthPx / 2f)
    val arrowWidthPx = arrowHeightPx * 2f

    val direction =
      when (arrowDirection) {
        SpeechBubbleArrowDirection.Start ->
          when (layoutDirection) {
            Ltr -> SpeechBubbleArrowAbsoluteDirection.Left
            Rtl -> SpeechBubbleArrowAbsoluteDirection.Right
          }
        SpeechBubbleArrowDirection.End ->
          when (layoutDirection) {
            Ltr -> SpeechBubbleArrowAbsoluteDirection.Right
            Rtl -> SpeechBubbleArrowAbsoluteDirection.Left
          }
        SpeechBubbleArrowDirection.Top -> SpeechBubbleArrowAbsoluteDirection.Top
        SpeechBubbleArrowDirection.Bottom -> SpeechBubbleArrowAbsoluteDirection.Bottom
      }

    val bubble =
      when (direction) {
        SpeechBubbleArrowAbsoluteDirection.Left -> Rect(arrowHeightPx, 0f, size.width, size.height)
        SpeechBubbleArrowAbsoluteDirection.Top -> Rect(0f, arrowHeightPx, size.width, size.height)
        SpeechBubbleArrowAbsoluteDirection.Right ->
          Rect(0f, 0f, size.width - arrowHeightPx, size.height)
        SpeechBubbleArrowAbsoluteDirection.Bottom ->
          Rect(0f, 0f, size.width, size.height - arrowHeightPx)
      }

    val innerBubble =
      Rect(
        bubble.left + cornerPx,
        bubble.top + cornerPx,
        bubble.right - cornerPx,
        bubble.bottom - cornerPx,
      )

    val absoluteBias =
      when (layoutDirection) {
        Ltr -> arrowPlacementBias
        Rtl ->
          when (arrowDirection) {
            SpeechBubbleArrowDirection.Top,
            SpeechBubbleArrowDirection.Bottom -> 1f - arrowPlacementBias
            SpeechBubbleArrowDirection.Start,
            SpeechBubbleArrowDirection.End -> arrowPlacementBias
          }
      }

    val cornerDiameter = cornerPx * 2f
    val cornerCircle = Size(cornerDiameter, cornerDiameter)

    val path =
      Path().apply {
        moveTo(innerBubble.left, bubble.top)
        arcTo(Rect(bubble.topLeft, cornerCircle), 270f, -90f, false)
        if (direction == SpeechBubbleArrowAbsoluteDirection.Left) {
          val pos = (innerBubble.height - arrowWidthPx) * absoluteBias
          lineTo(bubble.left, innerBubble.top + pos)
          relativeLineTo(-arrowHeightPx, arrowWidthPx / 2f)
          relativeLineTo(arrowHeightPx, arrowWidthPx / 2f)
        }
        arcTo(Rect(bubble.bottomLeft - Offset(0f, cornerDiameter), cornerCircle), 180f, -90f, false)
        if (direction == SpeechBubbleArrowAbsoluteDirection.Bottom) {
          val pos = (innerBubble.width - arrowWidthPx) * absoluteBias
          lineTo(innerBubble.left + pos, bubble.bottom)
          relativeLineTo(arrowWidthPx / 2f, arrowHeightPx)
          relativeLineTo(arrowWidthPx / 2f, -arrowHeightPx)
        }
        arcTo(
          Rect(bubble.bottomRight - Offset(cornerDiameter, cornerDiameter), cornerCircle),
          90f,
          -90f,
          false,
        )
        if (direction == SpeechBubbleArrowAbsoluteDirection.Right) {
          val pos = (innerBubble.height - arrowWidthPx) * absoluteBias + arrowWidthPx
          lineTo(bubble.right, innerBubble.top + pos)
          relativeLineTo(arrowHeightPx, -arrowWidthPx / 2f)
          relativeLineTo(-arrowHeightPx, -arrowWidthPx / 2f)
        }
        arcTo(Rect(bubble.topRight - Offset(cornerDiameter, 0f), cornerCircle), 0f, -90f, false)
        if (direction == SpeechBubbleArrowAbsoluteDirection.Top) {
          val pos = (innerBubble.width - arrowWidthPx) * absoluteBias + arrowWidthPx
          lineTo(innerBubble.left + pos, bubble.top)
          relativeLineTo(-arrowWidthPx / 2f, -arrowHeightPx)
          relativeLineTo(-arrowWidthPx / 2f, arrowHeightPx)
        }
        close()
      }
    return Outline.Generic(path)
  }
}
