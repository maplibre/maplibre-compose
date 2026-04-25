package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toJsonString

internal actual class HeatmapLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val layerId: String = id
  override fun layerType(): String = "heatmap"

  actual override var sourceLayer: String
    get() = sourceLayerString()
    set(value) { updateSourceLayer(value) }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    updateFilter(filter)
  }

  actual fun setHeatmapRadius(radius: CompiledExpression<DpValue>) {
    setPaintProp("heatmap-radius", radius.toJsonString())
  }

  actual fun setHeatmapWeight(weight: CompiledExpression<FloatValue>) {
    setPaintProp("heatmap-weight", weight.toJsonString())
  }

  actual fun setHeatmapIntensity(intensity: CompiledExpression<FloatValue>) {
    setPaintProp("heatmap-intensity", intensity.toJsonString())
  }

  actual fun setHeatmapColor(color: CompiledExpression<ColorValue>) {
    setPaintProp("heatmap-color", color.toJsonString())
  }

  actual fun setHeatmapOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProp("heatmap-opacity", opacity.toJsonString())
  }
}
