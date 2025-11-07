package org.maplibre.compose.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset

/**
 * Options for resizing an image, specifying insets (non-stretchable areas) around the edges.
 *
 * @property left The size of the non-stretchable area on the left side of the image
 * @property top The size of the non-stretchable area at the top of the image
 * @property right The size of the non-stretchable area on the right side of the image
 * @property bottom The size of the non-stretchable area at the bottom of the image
 */
public data class ImageResizeOptions(
  val left: Dp,
  val top: Dp,
  val right: Dp,
  val bottom: Dp,
  // MapLibre Native and JS also support specifying stretchX and stretchY ranges, but this isn't
  // exposed on the iOS SDK. So we can't support it until we have native core integration on iOS.
  // TODO val stretchX: List<IntRange> = emptyList(),
  // TODO val stretchY: List<IntRange> = emptyList(),
) {
  /**
   * Creates resize options using [DpOffset] objects for the left/top and right/bottom insets.
   *
   * @param leftTop The insets for the left and top edges
   * @param rightBottom The insets for the right and bottom edges
   */
  public constructor(
    leftTop: DpOffset,
    rightBottom: DpOffset,
  ) : this(leftTop.x, leftTop.y, rightBottom.x, rightBottom.y)

  /** The insets for the left and top edges as a [DpOffset] */
  public val leftTop: DpOffset
    get() = DpOffset(left, top)

  /** The insets for the right and bottom edges as a [DpOffset] */
  public val rightBottom: DpOffset
    get() = DpOffset(right, bottom)
}
