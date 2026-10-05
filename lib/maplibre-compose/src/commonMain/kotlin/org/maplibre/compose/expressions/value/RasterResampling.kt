package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * The resampling/interpolation method to use for overscaling, also known as texture magnification
 * filter
 */
@JvmInline
public value class RasterResampling private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<RasterResampling> {
    /**
     * (Bi)linear filtering interpolates pixel values using the weighted average of the four closest
     * original source pixels creating a smooth but blurry look when overscaled
     */
    public val Linear: RasterResampling = RasterResampling("linear")

    /**
     * Nearest neighbor filtering interpolates pixel values using the nearest original source pixel
     * creating a sharp but pixelated look when overscaled
     */
    public val Nearest: RasterResampling = RasterResampling("nearest")

    public override val entries: List<RasterResampling> = listOf(Linear, Nearest)
  }
}
