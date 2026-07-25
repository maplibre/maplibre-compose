package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.sources.Source

internal actual class HeatmapLayer actual constructor(id: String, source: Source) :
  FeatureLayer(id, source) {

  override val type: String = "heatmap"

  actual override var sourceLayer: String = ""
    set(value) {
      field = value
      setSourceLayerProperty(value)
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    setFilterExpression(filter)
  }

  actual fun setHeatmapRadius(radius: CompiledExpression<DpValue>) {
    setPaintProperty("heatmap-radius", radius)
  }

  actual fun setHeatmapWeight(weight: CompiledExpression<FloatValue>) {
    setPaintProperty("heatmap-weight", weight)
  }

  actual fun setHeatmapIntensity(intensity: CompiledExpression<FloatValue>) {
    setPaintProperty("heatmap-intensity", intensity)
  }

  actual fun setHeatmapColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("heatmap-color", color)
  }

  actual fun setHeatmapOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("heatmap-opacity", opacity)
  }
}
