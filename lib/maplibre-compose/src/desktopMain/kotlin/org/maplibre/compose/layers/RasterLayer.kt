package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.MillisecondsValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toJsonString
import org.maplibre.kmp.native.style.layers.RasterLayer as MLNRasterLayer

internal actual class RasterLayer actual constructor(id: String, actual val source: Source) :
  Layer() {
  override val impl = MLNRasterLayer(id, source.id)

  actual fun setRasterOpacity(opacity: CompiledExpression<FloatValue>) {
    opacity.toJsonString()?.let { impl.setProperty("raster-opacity", it) }
  }

  actual fun setRasterHueRotate(hueRotate: CompiledExpression<FloatValue>) {
    hueRotate.toJsonString()?.let { impl.setProperty("raster-hue-rotate", it) }
  }

  actual fun setRasterBrightnessMin(brightnessMin: CompiledExpression<FloatValue>) {
    brightnessMin.toJsonString()?.let { impl.setProperty("raster-brightness-min", it) }
  }

  actual fun setRasterBrightnessMax(brightnessMax: CompiledExpression<FloatValue>) {
    brightnessMax.toJsonString()?.let { impl.setProperty("raster-brightness-max", it) }
  }

  actual fun setRasterSaturation(saturation: CompiledExpression<FloatValue>) {
    saturation.toJsonString()?.let { impl.setProperty("raster-saturation", it) }
  }

  actual fun setRasterContrast(contrast: CompiledExpression<FloatValue>) {
    contrast.toJsonString()?.let { impl.setProperty("raster-contrast", it) }
  }

  actual fun setRasterResampling(resampling: CompiledExpression<RasterResampling>) {
    resampling.toJsonString()?.let { impl.setProperty("raster-resampling", it) }
  }

  actual fun setRasterFadeDuration(fadeDuration: CompiledExpression<MillisecondsValue>) {
    fadeDuration.toJsonString()?.let { impl.setProperty("raster-fade-duration", it) }
  }
}
