package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection

/** Stretch and content-box metadata for a style image used with `icon-text-fit`. */
@Immutable
public sealed class ImageStretch {
  public companion object {
    /**
     * Stretch intervals and an optional text box, from the top-left of the image.
     *
     * Empty [x] or [y] omits stretch on that axis. Overlapping or out-of-image ranges on an axis
     * are omitted. An out-of-image [content] box is omitted.
     *
     * @param x Horizontal stretch intervals.
     * @param y Vertical stretch intervals.
     * @param content Box that `icon-text-fit` fills. When omitted, MapLibre uses the whole image.
     */
    public operator fun invoke(
      x: List<ClosedRange<Dp>>,
      y: List<ClosedRange<Dp>>,
      content: DpRect? = null,
    ): ImageStretch = Ranges(x.toList(), y.toList(), content)

    /** Fixed insets on each edge. The interior stretches and receives text. */
    public fun capInsets(left: Dp, top: Dp, right: Dp, bottom: Dp): ImageStretch =
      capInsets(
        stretch = PaddingValues.Absolute(left, top, right, bottom),
        content = PaddingValues.Absolute(left, top, right, bottom),
      )

    /**
     * @param stretch Fixed border on each edge.
     * @param content Inset of the text box.
     */
    public fun capInsets(
      stretch: PaddingValues.Absolute,
      content: PaddingValues.Absolute,
    ): ImageStretch =
      CapInsets(
        stretchLeft = stretch.edgeLeft(),
        stretchTop = stretch.calculateTopPadding(),
        stretchRight = stretch.edgeRight(),
        stretchBottom = stretch.calculateBottomPadding(),
        contentLeft = content.edgeLeft(),
        contentTop = content.calculateTopPadding(),
        contentRight = content.edgeRight(),
        contentBottom = content.calculateBottomPadding(),
      )
  }

  private data class Ranges(
    val x: List<ClosedRange<Dp>>,
    val y: List<ClosedRange<Dp>>,
    val content: DpRect?,
  ) : ImageStretch() {
    override fun toString(): String = "ImageStretch(x=$x, y=$y, content=$content)"
  }

  private data class CapInsets(
    val stretchLeft: Dp,
    val stretchTop: Dp,
    val stretchRight: Dp,
    val stretchBottom: Dp,
    val contentLeft: Dp,
    val contentTop: Dp,
    val contentRight: Dp,
    val contentBottom: Dp,
  ) : ImageStretch() {
    private val contentEqualsStretch: Boolean
      get() =
        contentLeft == stretchLeft &&
          contentTop == stretchTop &&
          contentRight == stretchRight &&
          contentBottom == stretchBottom

    override fun toString(): String =
      if (contentEqualsStretch) {
        "ImageStretch.capInsets(left=$stretchLeft, top=$stretchTop, " +
          "right=$stretchRight, bottom=$stretchBottom)"
      } else {
        "ImageStretch.capInsets(stretch=[$stretchLeft, $stretchTop, $stretchRight, " +
          "$stretchBottom], content=[$contentLeft, $contentTop, $contentRight, $contentBottom])"
      }
  }

  internal fun resolve(imageWidth: Int, imageHeight: Int, scale: Float): ImageStretchPixels =
    when (this) {
      is CapInsets -> resolveCapInsets(imageWidth, imageHeight, scale)
      is Ranges -> resolveRanges(imageWidth, imageHeight, scale)
    }

  private fun CapInsets.resolveCapInsets(
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
  ): ImageStretchPixels {
    val stretchBox =
      insetBox(
        imageWidth,
        imageHeight,
        scale,
        stretchLeft,
        stretchTop,
        stretchRight,
        stretchBottom,
      )
    val contentBox =
      insetBox(
        imageWidth,
        imageHeight,
        scale,
        contentLeft,
        contentTop,
        contentRight,
        contentBottom,
      )
    val stretchOk = stretchBox.fits(imageWidth, imageHeight)
    val contentOk = contentBox.fits(imageWidth, imageHeight)
    return ImageStretchPixels(
      stretchX = if (stretchOk) listOf(stretchBox.left to stretchBox.right) else emptyList(),
      stretchY = if (stretchOk) listOf(stretchBox.top to stretchBox.bottom) else emptyList(),
      content = contentBox.takeIf { contentOk },
    )
  }

  private fun Ranges.resolveRanges(
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
  ): ImageStretchPixels {
    val stretchX = x.toIntervals(axisLength = imageWidth, scale = scale)
    val stretchY = y.toIntervals(axisLength = imageHeight, scale = scale)
    val content = content?.let { rect ->
      with(Density(scale)) {
        Rect(rect.left.toPx(), rect.top.toPx(), rect.right.toPx(), rect.bottom.toPx())
      }
    }
    return ImageStretchPixels(
      stretchX = stretchX.orEmpty(),
      stretchY = stretchY.orEmpty(),
      content = content?.takeIf { it.fits(imageWidth, imageHeight) },
    )
  }
}

internal data class ImageStretchPixels(
  val stretchX: List<Pair<Float, Float>> = emptyList(),
  val stretchY: List<Pair<Float, Float>> = emptyList(),
  val content: Rect? = null,
)

private fun PaddingValues.Absolute.edgeLeft(): Dp = calculateLeftPadding(LayoutDirection.Ltr)

private fun PaddingValues.Absolute.edgeRight(): Dp = calculateRightPadding(LayoutDirection.Ltr)

private fun insetBox(
  imageWidth: Int,
  imageHeight: Int,
  scale: Float,
  left: Dp,
  top: Dp,
  right: Dp,
  bottom: Dp,
): Rect =
  with(Density(scale)) {
    Rect(left.toPx(), top.toPx(), imageWidth - right.toPx(), imageHeight - bottom.toPx())
  }

private fun Rect.fits(imageWidth: Int, imageHeight: Int): Boolean =
  left < right &&
    top < bottom &&
    left >= 0f &&
    top >= 0f &&
    right <= imageWidth &&
    bottom <= imageHeight

private fun List<ClosedRange<Dp>>.toIntervals(
  axisLength: Int,
  scale: Float,
): List<Pair<Float, Float>>? {
  if (isEmpty()) return emptyList()
  val intervals =
    with(Density(scale)) { map { it.start.toPx() to it.endInclusive.toPx() } }.sortedBy { it.first }
  var previousEnd = Float.NEGATIVE_INFINITY
  for ((start, end) in intervals) {
    if (start < 0f || end > axisLength || start >= end || start < previousEnd) return null
    previousEnd = end
  }
  return intervals
}
