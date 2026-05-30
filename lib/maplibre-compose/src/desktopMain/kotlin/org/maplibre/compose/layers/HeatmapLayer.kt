package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toJsonString
import org.maplibre.kmp.native.style.layers.HeatmapLayer as MLNHeatmapLayer

internal actual class HeatmapLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = MLNHeatmapLayer(id, source.id)

  actual override var sourceLayer: String
    get() = impl.sourceLayer
    set(value) {
      impl.sourceLayer = value
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    filter.toJsonString()?.let { impl.setFilter(it) }
  }

  actual fun setHeatmapRadius(radius: CompiledExpression<DpValue>) {
    radius.toJsonString()?.let { impl.setProperty("heatmap-radius", it) }
  }

  actual fun setHeatmapWeight(weight: CompiledExpression<FloatValue>) {
    weight.toJsonString()?.let { impl.setProperty("heatmap-weight", it) }
  }

  actual fun setHeatmapIntensity(intensity: CompiledExpression<FloatValue>) {
    intensity.toJsonString()?.let { impl.setProperty("heatmap-intensity", it) }
  }

  actual fun setHeatmapColor(color: CompiledExpression<ColorValue>) {
    color.toJsonString()?.let { impl.setProperty("heatmap-color", it) }
  }

  actual fun setHeatmapOpacity(opacity: CompiledExpression<FloatValue>) {
    opacity.toJsonString()?.let { impl.setProperty("heatmap-opacity", it) }
  }
}
