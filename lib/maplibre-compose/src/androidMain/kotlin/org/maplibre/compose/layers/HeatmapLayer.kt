package org.maplibre.compose.layers

import org.maplibre.android.style.expressions.Expression as MLNExpression
import org.maplibre.android.style.layers.HeatmapLayer as MLNHeatmapLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toMLNExpression

internal actual class HeatmapLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = MLNHeatmapLayer(id, source.id)

  actual override var sourceLayer: String by impl::sourceLayer

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    impl.setFilter(filter.toMLNExpression() ?: MLNExpression.literal(true))
  }

  actual fun setHeatmapRadius(radius: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.heatmapRadius(radius.toMLNExpression()))
  }

  actual fun setHeatmapWeight(weight: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.heatmapWeight(weight.toMLNExpression()))
  }

  actual fun setHeatmapIntensity(intensity: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.heatmapIntensity(intensity.toMLNExpression()))
  }

  actual fun setHeatmapColor(color: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.heatmapColor(color.toMLNExpression()))
  }

  actual fun setHeatmapOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.heatmapOpacity(opacity.toMLNExpression()))
  }
}
