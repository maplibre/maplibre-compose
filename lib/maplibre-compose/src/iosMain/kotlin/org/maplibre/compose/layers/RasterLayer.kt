package org.maplibre.compose.layers

import MapLibre.MLNRasterStyleLayer
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.MillisecondsValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toNSExpression

internal actual class RasterLayer actual constructor(id: String, actual val source: Source) :
  Layer() {

  override val impl = MLNRasterStyleLayer(id, source.impl)

  actual fun setRasterOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.rasterOpacity = opacity.toNSExpression()
  }

  actual fun setRasterHueRotate(hueRotate: CompiledExpression<FloatValue>) {
    impl.rasterHueRotation = hueRotate.toNSExpression()
  }

  actual fun setRasterBrightnessMin(brightnessMin: CompiledExpression<FloatValue>) {
    impl.minimumRasterBrightness = brightnessMin.toNSExpression()
  }

  actual fun setRasterBrightnessMax(brightnessMax: CompiledExpression<FloatValue>) {
    impl.maximumRasterBrightness = brightnessMax.toNSExpression()
  }

  actual fun setRasterSaturation(saturation: CompiledExpression<FloatValue>) {
    impl.rasterSaturation = saturation.toNSExpression()
  }

  actual fun setRasterContrast(contrast: CompiledExpression<FloatValue>) {
    impl.rasterContrast = contrast.toNSExpression()
  }

  actual fun setRasterResampling(resampling: CompiledExpression<RasterResampling>) {
    impl.rasterResamplingMode = resampling.toNSExpression()
  }

  actual fun setRasterFadeDuration(fadeDuration: CompiledExpression<MillisecondsValue>) {
    impl.rasterFadeDuration = fadeDuration.toNSExpression()
  }
}
