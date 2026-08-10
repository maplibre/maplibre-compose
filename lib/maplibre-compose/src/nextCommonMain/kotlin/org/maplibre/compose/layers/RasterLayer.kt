package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.MillisecondsValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.sources.Source

internal actual class RasterLayer actual constructor(id: String, actual val source: Source) :
  Layer(id) {

  override val type: String = "raster"

  override val sourceId: String = source.id

  override val sourceDescriptor: Source
    get() = source

  actual fun setRasterOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-opacity", opacity)
  }

  actual fun setRasterHueRotate(hueRotate: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-hue-rotate", hueRotate)
  }

  actual fun setRasterBrightnessMin(brightnessMin: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-brightness-min", brightnessMin)
  }

  actual fun setRasterBrightnessMax(brightnessMax: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-brightness-max", brightnessMax)
  }

  actual fun setRasterSaturation(saturation: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-saturation", saturation)
  }

  actual fun setRasterContrast(contrast: CompiledExpression<FloatValue>) {
    setPaintProperty("raster-contrast", contrast)
  }

  actual fun setRasterResampling(resampling: CompiledExpression<RasterResampling>) {
    setPaintProperty("raster-resampling", resampling)
  }

  actual fun setRasterFadeDuration(fadeDuration: CompiledExpression<MillisecondsValue>) {
    setPaintProperty("raster-fade-duration", fadeDuration)
  }
}
