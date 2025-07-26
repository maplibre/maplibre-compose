package org.maplibre.compose.layers

import org.maplibre.android.style.expressions.Expression as MLNExpression
import org.maplibre.android.style.layers.CircleLayer as MLNCircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.CirclePitchAlignment
import org.maplibre.compose.expressions.value.CirclePitchScale
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toMLNExpression

internal actual class CircleLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = MLNCircleLayer(id, source.id)

  actual override var sourceLayer: String by impl::sourceLayer

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    impl.setFilter(filter.toMLNExpression() ?: MLNExpression.literal(true))
  }

  actual fun setCircleSortKey(sortKey: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.circleSortKey(sortKey.toMLNExpression()))
  }

  actual fun setCircleRadius(radius: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.circleRadius(radius.toMLNExpression()))
  }

  actual fun setCircleColor(color: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.circleColor(color.toMLNExpression()))
  }

  actual fun setCircleBlur(blur: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.circleBlur(blur.toMLNExpression()))
  }

  actual fun setCircleOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.circleOpacity(opacity.toMLNExpression()))
  }

  actual fun setCircleTranslate(translate: CompiledExpression<DpOffsetValue>) {
    impl.setProperties(PropertyFactory.circleTranslate(translate.toMLNExpression()))
  }

  actual fun setCircleTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    impl.setProperties(PropertyFactory.circleTranslateAnchor(translateAnchor.toMLNExpression()))
  }

  actual fun setCirclePitchScale(pitchScale: CompiledExpression<CirclePitchScale>) {
    impl.setProperties(PropertyFactory.circlePitchScale(pitchScale.toMLNExpression()))
  }

  actual fun setCirclePitchAlignment(pitchAlignment: CompiledExpression<CirclePitchAlignment>) {
    impl.setProperties(PropertyFactory.circlePitchAlignment(pitchAlignment.toMLNExpression()))
  }

  actual fun setCircleStrokeWidth(strokeWidth: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.circleStrokeWidth(strokeWidth.toMLNExpression()))
  }

  actual fun setCircleStrokeColor(strokeColor: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.circleStrokeColor(strokeColor.toMLNExpression()))
  }

  actual fun setCircleStrokeOpacity(strokeOpacity: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.circleStrokeOpacity(strokeOpacity.toMLNExpression()))
  }
}
