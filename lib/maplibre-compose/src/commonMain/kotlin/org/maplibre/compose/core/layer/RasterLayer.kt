package org.maplibre.compose.core.layer

import org.maplibre.compose.core.source.Source
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.MillisecondsValue
import org.maplibre.compose.expressions.value.RasterResampling

internal expect class RasterLayer(id: String, source: Source) : Layer {
  val source: Source

  fun setRasterOpacity(opacity: CompiledExpression<FloatValue>)

  fun setRasterHueRotate(hueRotate: CompiledExpression<FloatValue>)

  fun setRasterBrightnessMin(brightnessMin: CompiledExpression<FloatValue>)

  fun setRasterBrightnessMax(brightnessMax: CompiledExpression<FloatValue>)

  fun setRasterSaturation(saturation: CompiledExpression<FloatValue>)

  fun setRasterContrast(contrast: CompiledExpression<FloatValue>)

  fun setRasterResampling(resampling: CompiledExpression<RasterResampling>)

  fun setRasterFadeDuration(fadeDuration: CompiledExpression<MillisecondsValue>)
}
