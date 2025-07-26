package org.maplibre.compose.layers

import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer as MLNRasterLayer
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.MillisecondsValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toMLNExpression

internal actual class RasterLayer actual constructor(id: String, actual val source: Source) :
  Layer() {
  override val impl = MLNRasterLayer(id, source.id)

  actual fun setRasterOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.rasterOpacity(opacity.toMLNExpression()))
  }

  actual fun setRasterHueRotate(hueRotate: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.rasterHueRotate(hueRotate.toMLNExpression()))
  }

  actual fun setRasterBrightnessMin(brightnessMin: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.rasterBrightnessMin(brightnessMin.toMLNExpression()))
  }

  actual fun setRasterBrightnessMax(brightnessMax: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.rasterBrightnessMax(brightnessMax.toMLNExpression()))
  }

  actual fun setRasterSaturation(saturation: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.rasterSaturation(saturation.toMLNExpression()))
  }

  actual fun setRasterContrast(contrast: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.rasterContrast(contrast.toMLNExpression()))
  }

  actual fun setRasterResampling(resampling: CompiledExpression<RasterResampling>) {
    impl.setProperties(PropertyFactory.rasterResampling(resampling.toMLNExpression()))
  }

  actual fun setRasterFadeDuration(fadeDuration: CompiledExpression<MillisecondsValue>) {
    impl.setProperties(PropertyFactory.rasterFadeDuration(fadeDuration.toMLNExpression()))
  }
}
