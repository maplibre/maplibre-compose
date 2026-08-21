package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import kotlin.math.ceil
import kotlin.math.max

/** The size of a map surface, in both logical and physical pixels. */
@Immutable
internal class MapExtent
private constructor(
  /** Width in logical pixels. */
  val width: Int,
  /** Height in logical pixels. */
  val height: Int,
  /** Physical pixels per logical pixel. */
  val scaleFactor: Double,
  /** Width in physical pixels. */
  val physicalWidth: Int,
  /** Height in physical pixels. */
  val physicalHeight: Int,
) {
  /**
   * Whether this extent describes nothing renderable. Compose reports a zero size before first
   * layout, and MapLibre rejects such an extent, so skip work rather than passing it on.
   */
  val isEmpty: Boolean
    get() =
      width <= 0 ||
        height <= 0 ||
        physicalWidth <= 0 ||
        physicalHeight <= 0 ||
        !scaleFactor.isFinite() ||
        scaleFactor <= 0.0

  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is MapExtent &&
        width == other.width &&
        height == other.height &&
        scaleFactor == other.scaleFactor &&
        physicalWidth == other.physicalWidth &&
        physicalHeight == other.physicalHeight)

  override fun hashCode(): Int {
    var result = width
    result = 31 * result + height
    result = 31 * result + scaleFactor.hashCode()
    result = 31 * result + physicalWidth
    result = 31 * result + physicalHeight
    return result
  }

  override fun toString(): String =
    "MapExtent(logical=${width}x$height, physical=${physicalWidth}x$physicalHeight, " +
      "scale=$scaleFactor)"

  companion object {
    val Empty: MapExtent = MapExtent(0, 0, 1.0, 0, 0)

    fun fromLogical(width: Int, height: Int, scaleFactor: Double): MapExtent {
      val scale = normalizeScale(scaleFactor)
      if (width <= 0 || height <= 0) return Empty
      return MapExtent(
        width = width,
        height = height,
        scaleFactor = scale,
        physicalWidth = physicalDimension(width, scale),
        physicalHeight = physicalDimension(height, scale),
      )
    }

    /**
     * The physical size is kept exactly as reported: re-deriving it from the logical size would
     * magnify floating-point noise in a fractional scale factor and could make the map texture one
     * pixel larger than the Compose canvas.
     */
    fun fromPhysical(physicalWidth: Int, physicalHeight: Int, scaleFactor: Double): MapExtent {
      val scale = normalizeScale(scaleFactor)
      if (physicalWidth <= 0 || physicalHeight <= 0) return Empty
      val logicalWidth = max(1, ceil(physicalWidth / scale).toInt())
      val logicalHeight = max(1, ceil(physicalHeight / scale).toInt())
      return MapExtent(
        width = logicalWidth,
        height = logicalHeight,
        scaleFactor = scale,
        physicalWidth = physicalWidth,
        physicalHeight = physicalHeight,
      )
    }

    private fun normalizeScale(scaleFactor: Double): Double =
      if (scaleFactor.isFinite() && scaleFactor > 0.0) scaleFactor else 1.0

    private fun physicalDimension(logical: Int, scale: Double): Int =
      max(1, ceil(logical * scale).toInt())
  }
}
