package org.maplibre.compose.core.layer

import org.maplibre.compose.core.source.Source
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.TranslateAnchor

internal expect class FillExtrusionLayer(id: String, source: Source) : FeatureLayer {
  override var sourceLayer: String

  override fun setFilter(filter: CompiledExpression<BooleanValue>)

  fun setFillExtrusionOpacity(opacity: CompiledExpression<FloatValue>)

  fun setFillExtrusionColor(color: CompiledExpression<ColorValue>)

  fun setFillExtrusionTranslate(translate: CompiledExpression<DpOffsetValue>)

  fun setFillExtrusionTranslateAnchor(anchor: CompiledExpression<TranslateAnchor>)

  fun setFillExtrusionPattern(pattern: CompiledExpression<ImageValue>)

  fun setFillExtrusionHeight(height: CompiledExpression<FloatValue>)

  fun setFillExtrusionBase(base: CompiledExpression<FloatValue>)

  fun setFillExtrusionVerticalGradient(verticalGradient: CompiledExpression<BooleanValue>)
}
