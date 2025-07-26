package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/**
 * The resampling/interpolation method to use for overscaling, also known as texture magnification
 * filter
 */
public enum class RasterResampling(override val literal: StringLiteral) :
  EnumValue<RasterResampling> {
  /**
   * (Bi)linear filtering interpolates pixel values using the weighted average of the four closest
   * original source pixels creating a smooth but blurry look when overscaled
   */
  Linear(StringLiteral.of("linear")),

  /**
   * Nearest neighbor filtering interpolates pixel values using the nearest original source pixel
   * creating a sharp but pixelated look when overscaled
   */
  Nearest(StringLiteral.of("nearest")),
}
