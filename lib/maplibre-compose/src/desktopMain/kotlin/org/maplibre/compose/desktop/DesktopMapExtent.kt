package org.maplibre.compose.desktop

import androidx.compose.runtime.Immutable
import kotlin.math.ceil
import kotlin.math.max

/**
 * The size of a desktop map surface, in both logical and physical pixels.
 *
 * MapLibre Native takes a logical size plus a scale factor, while GPU render targets are allocated
 * in physical pixels. Carrying both together, derived once, keeps the two from drifting apart under
 * fractional display scaling — a mismatch of a single pixel produces a visibly stretched map.
 */
@Immutable
public class DesktopMapExtent
private constructor(
  /** Width in logical pixels, as Compose measures it. */
  public val width: Int,
  /** Height in logical pixels, as Compose measures it. */
  public val height: Int,
  /** Display scale factor; physical pixels per logical pixel. */
  public val scaleFactor: Double,
  /** Width in physical pixels, as the GPU allocates it. */
  public val physicalWidth: Int,
  /** Height in physical pixels, as the GPU allocates it. */
  public val physicalHeight: Int,
) {
  /**
   * Whether this extent describes nothing renderable.
   *
   * Compose reports a zero size before first layout, so a map surface is normally empty for at
   * least one frame. Hosts and sessions skip work rather than treating it as an error.
   *
   * Not defensive programming: MapLibre rejects a zero extent outright, with `map dimensions and
   * scale_factor must be positive` from map creation and `texture dimensions and scale_factor must
   * be positive` from attach. Both are measured. Since the empty frame is routine rather than
   * exceptional, it is checked for rather than caught.
   */
  public val isEmpty: Boolean
    get() =
      width <= 0 ||
        height <= 0 ||
        physicalWidth <= 0 ||
        physicalHeight <= 0 ||
        !scaleFactor.isFinite() ||
        scaleFactor <= 0.0

  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is DesktopMapExtent &&
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
    "DesktopMapExtent(logical=${width}x$height, physical=${physicalWidth}x$physicalHeight, " +
      "scale=$scaleFactor)"

  public companion object {
    /** An extent with no renderable area. */
    public val Empty: DesktopMapExtent = DesktopMapExtent(0, 0, 1.0, 0, 0)

    /**
     * Builds an extent from a logical size and scale factor, deriving the physical size.
     *
     * Use this when Compose gave you a size in dp-equivalent logical pixels.
     */
    public fun fromLogical(width: Int, height: Int, scaleFactor: Double): DesktopMapExtent {
      val scale = normalizeScale(scaleFactor)
      if (width <= 0 || height <= 0) return Empty
      return DesktopMapExtent(
        width = width,
        height = height,
        scaleFactor = scale,
        physicalWidth = physicalDimension(width, scale),
        physicalHeight = physicalDimension(height, scale),
      )
    }

    /**
     * Builds an extent from a physical size and scale factor, deriving the logical size.
     *
     * Use this when Compose gave you a size in physical pixels, which is what `onSizeChanged`
     * reports. The physical size is then re-derived from the rounded logical size so that the two
     * agree exactly, at the cost of being up to one pixel from the size that was passed in.
     */
    public fun fromPhysical(
      physicalWidth: Int,
      physicalHeight: Int,
      scaleFactor: Double,
    ): DesktopMapExtent {
      val scale = normalizeScale(scaleFactor)
      if (physicalWidth <= 0 || physicalHeight <= 0) return Empty
      val logicalWidth = max(1, ceil(physicalWidth / scale).toInt())
      val logicalHeight = max(1, ceil(physicalHeight / scale).toInt())
      return fromLogical(logicalWidth, logicalHeight, scale)
    }

    private fun normalizeScale(scaleFactor: Double): Double =
      if (scaleFactor.isFinite() && scaleFactor > 0.0) scaleFactor else 1.0

    private fun physicalDimension(logical: Int, scale: Double): Int =
      max(1, ceil(logical * scale).toInt())
  }
}
