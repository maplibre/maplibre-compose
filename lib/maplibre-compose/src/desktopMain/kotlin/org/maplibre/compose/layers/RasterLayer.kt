package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.MillisecondsValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toJsonString

internal actual class RasterLayer actual constructor(id: String, actual val source: Source) :
  Layer() {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val layerId: String = id
  override fun layerType(): String = "raster"
  override fun sourceId(): String = source.id

  actual fun setRasterOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProp("raster-opacity", opacity.toJsonString())
  }

  actual fun setRasterHueRotate(hueRotate: CompiledExpression<FloatValue>) {
    setPaintProp("raster-hue-rotate", hueRotate.toJsonString())
  }

  actual fun setRasterBrightnessMin(brightnessMin: CompiledExpression<FloatValue>) {
    setPaintProp("raster-brightness-min", brightnessMin.toJsonString())
  }

  actual fun setRasterBrightnessMax(brightnessMax: CompiledExpression<FloatValue>) {
    setPaintProp("raster-brightness-max", brightnessMax.toJsonString())
  }

  actual fun setRasterSaturation(saturation: CompiledExpression<FloatValue>) {
    setPaintProp("raster-saturation", saturation.toJsonString())
  }

  actual fun setRasterContrast(contrast: CompiledExpression<FloatValue>) {
    setPaintProp("raster-contrast", contrast.toJsonString())
  }

  actual fun setRasterResampling(resampling: CompiledExpression<RasterResampling>) {
    setPaintProp("raster-resampling", resampling.toJsonString())
  }

  actual fun setRasterFadeDuration(fadeDuration: CompiledExpression<MillisecondsValue>) {
    setPaintProp("raster-fade-duration", fadeDuration.toJsonString())
  }
}
