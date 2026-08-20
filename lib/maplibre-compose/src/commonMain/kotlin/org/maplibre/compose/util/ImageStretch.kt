package org.maplibre.compose.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect

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
    public fun capInsets(left: Dp, top: Dp, right: Dp, bottom: Dp): ImageStretch {
      val insets = PaddingValues.Absolute(left, top, right, bottom)
      return CapInsets(stretch = insets, content = insets)
    }

    /**
     * @param stretch Fixed border on each edge.
     * @param content Inset of the text box.
     */
    public fun capInsets(
      stretch: PaddingValues.Absolute,
      content: PaddingValues.Absolute,
    ): ImageStretch = CapInsets(stretch, content)
  }

  internal abstract fun resolve(
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
  ): ImageStretchPixels

  private data class Ranges(
    val x: List<ClosedRange<Dp>>,
    val y: List<ClosedRange<Dp>>,
    val content: DpRect?,
  ) : ImageStretch() {
    override fun resolve(imageWidth: Int, imageHeight: Int, scale: Float): ImageStretchPixels {
      val density = Density(scale)
      val contentPx = content?.let { rect ->
        with(density) {
          Rect(rect.left.toPx(), rect.top.toPx(), rect.right.toPx(), rect.bottom.toPx())
        }
      }
      return ImageStretchPixels(
        stretchX = stretchIntervals(x, imageWidth, density),
        stretchY = stretchIntervals(y, imageHeight, density),
        content = contentPx?.takeIf { it.fits(imageWidth, imageHeight) },
      )
    }

    override fun toString(): String = "ImageStretch(x=$x, y=$y, content=$content)"
  }

  private data class CapInsets(
    val stretch: PaddingValues.Absolute,
    val content: PaddingValues.Absolute,
  ) : ImageStretch() {
    override fun resolve(imageWidth: Int, imageHeight: Int, scale: Float): ImageStretchPixels {
      val density = Density(scale)
      val stretchBox = insetBox(stretch, imageWidth, imageHeight, density)
      val contentBox = insetBox(content, imageWidth, imageHeight, density)
      val stretchOk = stretchBox.fits(imageWidth, imageHeight)
      return ImageStretchPixels(
        stretchX = if (stretchOk) listOf(stretchBox.left to stretchBox.right) else emptyList(),
        stretchY = if (stretchOk) listOf(stretchBox.top to stretchBox.bottom) else emptyList(),
        content = contentBox.takeIf { it.fits(imageWidth, imageHeight) },
      )
    }

    override fun toString(): String =
      if (stretch == content) {
        "ImageStretch.capInsets(left=${stretch.left}, top=${stretch.top}, " +
          "right=${stretch.right}, bottom=${stretch.bottom})"
      } else {
        "ImageStretch.capInsets(stretch=$stretch, content=$content)"
      }
  }
}

internal data class ImageStretchPixels(
  val stretchX: List<Pair<Float, Float>> = emptyList(),
  val stretchY: List<Pair<Float, Float>> = emptyList(),
  val content: Rect? = null,
)

private fun insetBox(
  insets: PaddingValues.Absolute,
  imageWidth: Int,
  imageHeight: Int,
  density: Density,
): Rect =
  with(density) {
    Rect(
      insets.left.toPx(),
      insets.top.toPx(),
      imageWidth - insets.right.toPx(),
      imageHeight - insets.bottom.toPx(),
    )
  }

private fun Rect.fits(imageWidth: Int, imageHeight: Int): Boolean =
  left < right &&
    top < bottom &&
    left >= 0f &&
    top >= 0f &&
    right <= imageWidth &&
    bottom <= imageHeight

private fun stretchIntervals(
  ranges: List<ClosedRange<Dp>>,
  axisLength: Int,
  density: Density,
): List<Pair<Float, Float>> {
  if (ranges.isEmpty()) return emptyList()
  val intervals =
    with(density) { ranges.map { it.start.toPx() to it.endInclusive.toPx() } }.sortedBy { it.first }
  var previousEnd = Float.NEGATIVE_INFINITY
  for ((start, end) in intervals) {
    if (start < 0f || end > axisLength || start >= end || start < previousEnd) return emptyList()
    previousEnd = end
  }
  return intervals
}
