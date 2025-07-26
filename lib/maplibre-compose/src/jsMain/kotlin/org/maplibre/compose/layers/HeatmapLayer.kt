package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.sources.Source

internal actual class HeatmapLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = TODO()

  actual override var sourceLayer: String = TODO()

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setHeatmapRadius(radius: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setHeatmapWeight(weight: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setHeatmapIntensity(intensity: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setHeatmapColor(color: CompiledExpression<ColorValue>) {
    TODO()
  }

  actual fun setHeatmapOpacity(opacity: CompiledExpression<FloatValue>) {
    TODO()
  }
}
