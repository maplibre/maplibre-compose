package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.sources.Source

internal actual class ColorReliefLayer actual constructor(id: String, actual val source: Source) :
  Layer(id) {

  override val type: String = "color-relief"

  override val sourceId: String = source.id

  override val sourceDescriptor: Source
    get() = source

  actual fun setColorReliefColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("color-relief-color", color)
  }

  actual fun setColorReliefOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("color-relief-opacity", opacity)
  }

  actual fun setResampling(resampling: CompiledExpression<RasterResampling>) {
    setPaintProperty("resampling", resampling)
  }
}
