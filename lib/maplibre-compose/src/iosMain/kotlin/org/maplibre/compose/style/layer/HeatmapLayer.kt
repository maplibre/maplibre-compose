package org.maplibre.compose.style.layer

import MapLibre.MLNHeatmapStyleLayer
import org.maplibre.compose.style.expressions.ast.CompiledExpression
import org.maplibre.compose.style.expressions.value.BooleanValue
import org.maplibre.compose.style.expressions.value.ColorValue
import org.maplibre.compose.style.expressions.value.DpValue
import org.maplibre.compose.style.expressions.value.FloatValue
import org.maplibre.compose.style.source.Source
import org.maplibre.compose.util.toNSExpression
import org.maplibre.compose.util.toNSPredicate

internal actual class HeatmapLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {

  override val impl = MLNHeatmapStyleLayer(id, source.impl)

  actual override var sourceLayer: String
    get() = impl.sourceLayerIdentifier!!
    set(value) {
      impl.sourceLayerIdentifier = value
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    impl.predicate = filter.toNSPredicate()
  }

  actual fun setHeatmapRadius(radius: CompiledExpression<DpValue>) {
    impl.heatmapRadius = radius.toNSExpression()
  }

  actual fun setHeatmapWeight(weight: CompiledExpression<FloatValue>) {
    impl.heatmapWeight = weight.toNSExpression()
  }

  actual fun setHeatmapIntensity(intensity: CompiledExpression<FloatValue>) {
    impl.heatmapIntensity = intensity.toNSExpression()
  }

  actual fun setHeatmapColor(color: CompiledExpression<ColorValue>) {
    impl.heatmapColor = color.toNSExpression()
  }

  actual fun setHeatmapOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.heatmapOpacity = opacity.toNSExpression()
  }
}
