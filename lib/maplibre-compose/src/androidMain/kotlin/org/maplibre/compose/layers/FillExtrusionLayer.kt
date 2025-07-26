package org.maplibre.compose.layers

import org.maplibre.android.style.expressions.Expression as MLNExpression
import org.maplibre.android.style.layers.FillExtrusionLayer as MLNFillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toMLNExpression

internal actual class FillExtrusionLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = MLNFillExtrusionLayer(id, source.id)

  actual override var sourceLayer: String by impl::sourceLayer

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    impl.setFilter(filter.toMLNExpression() ?: MLNExpression.literal(true))
  }

  actual fun setFillExtrusionOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.fillExtrusionOpacity(opacity.toMLNExpression()))
  }

  actual fun setFillExtrusionColor(color: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.fillExtrusionColor(color.toMLNExpression()))
  }

  actual fun setFillExtrusionTranslate(translate: CompiledExpression<DpOffsetValue>) {
    impl.setProperties(PropertyFactory.fillExtrusionTranslate(translate.toMLNExpression()))
  }

  actual fun setFillExtrusionTranslateAnchor(anchor: CompiledExpression<TranslateAnchor>) {
    impl.setProperties(PropertyFactory.fillExtrusionTranslateAnchor(anchor.toMLNExpression()))
  }

  actual fun setFillExtrusionPattern(pattern: CompiledExpression<ImageValue>) {
    impl.setProperties(PropertyFactory.fillExtrusionPattern(pattern.toMLNExpression()))
  }

  actual fun setFillExtrusionHeight(height: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.fillExtrusionHeight(height.toMLNExpression()))
  }

  actual fun setFillExtrusionBase(base: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.fillExtrusionBase(base.toMLNExpression()))
  }

  actual fun setFillExtrusionVerticalGradient(verticalGradient: CompiledExpression<BooleanValue>) {
    impl.setProperties(
      PropertyFactory.fillExtrusionVerticalGradient(verticalGradient.toMLNExpression())
    )
  }
}
