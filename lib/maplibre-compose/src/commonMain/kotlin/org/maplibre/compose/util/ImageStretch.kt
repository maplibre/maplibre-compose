package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection

/**
 * Stretch and content-box metadata for a style image used with `icon-text-fit`.
 *
 * Distances use the density that the bitmap was rasterized at. At 2x, `8.dp` is 16 image pixels.
 */
@Immutable
public sealed class ImageStretch {
  public companion object {
    /**
     * Stretch intervals and an optional text box, measured from the top-left of the image.
     *
     * An empty [x] or [y] omits stretch metadata on that axis. Ranges on an axis must be
     * non-overlapping and lie inside the bitmap; an axis that fails those checks is uploaded
     * without stretch. A content box that does not lie inside the bitmap is omitted.
     *
     * @param x Horizontal intervals that may stretch, from the left edge.
     * @param y Vertical intervals that may stretch, from the top edge.
     * @param content The box that `icon-text-fit` fills with text. When omitted, MapLibre uses the
     *   whole image.
     */
    public operator fun invoke(
      x: List<ClosedRange<Dp>>,
      y: List<ClosedRange<Dp>>,
      content: DpRect? = null,
    ): ImageStretch = Ranges(x.toList(), y.toList(), content)

    /**
     * Fixed insets on each edge of the image. The interior stretches and receives text.
     *
     * @param left Unstretched inset from the left edge.
     * @param top Unstretched inset from the top edge.
     * @param right Unstretched inset from the right edge.
     * @param bottom Unstretched inset from the bottom edge.
     */
    public fun capInsets(left: Dp, top: Dp, right: Dp, bottom: Dp): ImageStretch =
      capInsets(
        stretch = PaddingValues.Absolute(left, top, right, bottom),
        content = PaddingValues.Absolute(left, top, right, bottom),
      )

    /**
     * Fixed stretch border and a text box, inset independently.
     *
     * @param stretch Unstretched border on each edge.
     * @param content Inset of the box that `icon-text-fit` fills with text.
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

  /**
   * Pixel intervals for [imageWidth]×[imageHeight] at [scale]. Axes or the content box that do not
   * fit are omitted, with a warning.
   */
  internal fun resolve(imageWidth: Int, imageHeight: Int, scale: Float): ImageStretchResolution =
    when (this) {
      is CapInsets -> resolveCapInsets(imageWidth, imageHeight, scale)
      is Ranges -> resolveRanges(imageWidth, imageHeight, scale)
    }

  private fun CapInsets.resolveCapInsets(
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
  ): ImageStretchResolution {
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
    val warning =
      when {
        !stretchOk && !contentOk ->
          "asked for content insets that leave nothing to stretch: at scale $scale they put the " +
            "box at $stretchBox in a ${imageWidth}x${imageHeight} image. The image is uploaded " +
            "whole and scales uniformly."
        !stretchOk ->
          "asked for cap insets that leave nothing to stretch in a ${imageWidth}x${imageHeight} " +
            "image at scale $scale. Stretch metadata is omitted."
        !contentOk ->
          "asked for a content box that does not fit a ${imageWidth}x${imageHeight} image at " +
            "scale $scale: $contentBox. The content box is omitted."
        else -> null
      }
    return ImageStretchResolution(
      pixels =
        ImageStretchPixels(
          stretchX = if (stretchOk) listOf(stretchBox.left to stretchBox.right) else emptyList(),
          stretchY = if (stretchOk) listOf(stretchBox.top to stretchBox.bottom) else emptyList(),
          content = contentBox.takeIf { contentOk },
        ),
      warning = warning,
    )
  }

  private fun Ranges.resolveRanges(
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
  ): ImageStretchResolution {
    val stretchX = x.toIntervals(axisLength = imageWidth, scale = scale)
    val stretchY = y.toIntervals(axisLength = imageHeight, scale = scale)
    val content = content?.let { rect ->
      with(Density(scale)) {
        ImageContentBox(
          left = rect.left.toPx(),
          top = rect.top.toPx(),
          right = rect.right.toPx(),
          bottom = rect.bottom.toPx(),
        )
      }
    }
    val contentOk = content == null || content.fits(imageWidth, imageHeight)
    val issues = buildList {
      if (stretchX == null) add("horizontal stretch ranges")
      if (stretchY == null) add("vertical stretch ranges")
      if (!contentOk) add("content box $content")
    }
    val warning =
      if (issues.isEmpty()) null
      else
        "asked for ${issues.joinToString(" and ")} that do not fit a ${imageWidth}x${imageHeight} " +
          "image at scale $scale. Invalid stretch axes and content boxes are omitted."
    return ImageStretchResolution(
      pixels =
        ImageStretchPixels(
          stretchX = stretchX.orEmpty(),
          stretchY = stretchY.orEmpty(),
          content = content.takeIf { contentOk },
        ),
      warning = warning,
    )
  }
}

internal data class ImageStretchResolution(
  val pixels: ImageStretchPixels,
  val warning: String? = null,
)

internal data class ImageStretchPixels(
  val stretchX: List<Pair<Float, Float>> = emptyList(),
  val stretchY: List<Pair<Float, Float>> = emptyList(),
  val content: ImageContentBox? = null,
)

internal data class ImageContentBox(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
) {
  val isValid: Boolean
    get() = left < right && top < bottom

  fun fits(imageWidth: Int, imageHeight: Int): Boolean =
    isValid && left >= 0f && top >= 0f && right <= imageWidth && bottom <= imageHeight
}

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
): ImageContentBox =
  with(Density(scale)) {
    ImageContentBox(
      left = left.toPx(),
      top = top.toPx(),
      right = imageWidth - right.toPx(),
      bottom = imageHeight - bottom.toPx(),
    )
  }

/**
 * Pixel intervals along one axis, or null when the ranges cannot be used. An empty list is valid
 * and means the axis has no stretch metadata.
 */
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
