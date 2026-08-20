package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection

/**
 * How a style image stretches when a symbol layer sizes the icon to wrap its text, and where that
 * text sits.
 *
 * [capInsets] is the nine-patch form: a fixed border on each edge, with stretch and text in the
 * interior. `ImageStretch(x, y, content)` names stretch intervals and the text box independently. A
 * speech-bubble caret uses that second form: two horizontal stretch bands with a fixed column
 * between them.
 *
 * Distances are in the same density that the bitmap was rasterized at. At 2x, `8.dp` is 16 image
 * pixels.
 *
 * Omit the stretch argument on [image][org.maplibre.compose.expressions.dsl.image] to upload the
 * bitmap without this metadata. The icon then scales uniformly.
 */
public class ImageStretch private constructor(private val spec: Spec) {

  override fun equals(other: Any?): Boolean = other is ImageStretch && spec == other.spec

  override fun hashCode(): Int = spec.hashCode()

  override fun toString(): String =
    when (spec) {
      is Spec.Ranges -> "ImageStretch(x=${spec.x}, y=${spec.y}, content=${spec.content})"
      is Spec.CapInsets ->
        if (spec.contentEqualsStretch) {
          "ImageStretch.capInsets(left=${spec.stretchLeft}, top=${spec.stretchTop}, " +
            "right=${spec.stretchRight}, bottom=${spec.stretchBottom})"
        } else {
          "ImageStretch.capInsets(stretch=[${spec.stretchLeft}, ${spec.stretchTop}, " +
            "${spec.stretchRight}, ${spec.stretchBottom}], content=[${spec.contentLeft}, " +
            "${spec.contentTop}, ${spec.contentRight}, ${spec.contentBottom}])"
        }
    }

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
    ): ImageStretch = ImageStretch(Spec.Ranges(x.toList(), y.toList(), content))

    /**
     * A nine-patch. [left], [top], [right], and [bottom] are the fixed border on each edge. The
     * interior both stretches and receives text.
     */
    public fun capInsets(left: Dp, top: Dp, right: Dp, bottom: Dp): ImageStretch =
      capInsets(
        stretch = PaddingValues.Absolute(left, top, right, bottom),
        content = PaddingValues.Absolute(left, top, right, bottom),
      )

    /**
     * A nine-patch whose text box is inset independently of the stretch border.
     *
     * [stretch] is the fixed border. [content] is the inset of the box that `icon-text-fit` fills
     * with text.
     */
    public fun capInsets(
      stretch: PaddingValues.Absolute,
      content: PaddingValues.Absolute,
    ): ImageStretch =
      ImageStretch(
        Spec.CapInsets(
          stretchLeft = stretch.edgeLeft(),
          stretchTop = stretch.calculateTopPadding(),
          stretchRight = stretch.edgeRight(),
          stretchBottom = stretch.calculateBottomPadding(),
          contentLeft = content.edgeLeft(),
          contentTop = content.calculateTopPadding(),
          contentRight = content.edgeRight(),
          contentBottom = content.calculateBottomPadding(),
        )
      )
  }

  private sealed class Spec {
    data class Ranges(
      val x: List<ClosedRange<Dp>>,
      val y: List<ClosedRange<Dp>>,
      val content: DpRect?,
    ) : Spec()

    data class CapInsets(
      val stretchLeft: Dp,
      val stretchTop: Dp,
      val stretchRight: Dp,
      val stretchBottom: Dp,
      val contentLeft: Dp,
      val contentTop: Dp,
      val contentRight: Dp,
      val contentBottom: Dp,
    ) : Spec() {
      val contentEqualsStretch: Boolean
        get() =
          contentLeft == stretchLeft &&
            contentTop == stretchTop &&
            contentRight == stretchRight &&
            contentBottom == stretchBottom
    }
  }

  /**
   * Turns this description into image-pixel intervals, or empty stretch and a warning when it does
   * not fit [imageWidth]×[imageHeight] at [scale].
   */
  internal fun resolve(imageWidth: Int, imageHeight: Int, scale: Float): ImageStretchResolution =
    when (val spec = spec) {
      is Spec.CapInsets -> resolveCapInsets(spec, imageWidth, imageHeight, scale)
      is Spec.Ranges -> resolveRanges(spec, imageWidth, imageHeight, scale)
    }

  private fun resolveCapInsets(
    spec: Spec.CapInsets,
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
  ): ImageStretchResolution {
    val stretchBox =
      insetBox(
        imageWidth,
        imageHeight,
        scale,
        spec.stretchLeft,
        spec.stretchTop,
        spec.stretchRight,
        spec.stretchBottom,
      )
    val contentBox =
      insetBox(
        imageWidth,
        imageHeight,
        scale,
        spec.contentLeft,
        spec.contentTop,
        spec.contentRight,
        spec.contentBottom,
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

  private fun resolveRanges(
    spec: Spec.Ranges,
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
  ): ImageStretchResolution {
    val stretchX = spec.x.toIntervals(axisLength = imageWidth, scale = scale)
    val stretchY = spec.y.toIntervals(axisLength = imageHeight, scale = scale)
    val content =
      spec.content?.let { rect ->
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
