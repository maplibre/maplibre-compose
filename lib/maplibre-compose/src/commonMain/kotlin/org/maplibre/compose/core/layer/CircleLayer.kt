package org.maplibre.compose.core.layer

import org.maplibre.compose.core.source.Source
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.CirclePitchAlignment
import org.maplibre.compose.expressions.value.CirclePitchScale
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.TranslateAnchor

internal expect class CircleLayer(id: String, source: Source) : FeatureLayer {
  override var sourceLayer: String

  override fun setFilter(filter: CompiledExpression<BooleanValue>)

  fun setCircleSortKey(sortKey: CompiledExpression<FloatValue>)

  fun setCircleRadius(radius: CompiledExpression<DpValue>)

  fun setCircleColor(color: CompiledExpression<ColorValue>)

  fun setCircleBlur(blur: CompiledExpression<FloatValue>)

  fun setCircleOpacity(opacity: CompiledExpression<FloatValue>)

  fun setCircleTranslate(translate: CompiledExpression<DpOffsetValue>)

  fun setCircleTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>)

  fun setCirclePitchScale(pitchScale: CompiledExpression<CirclePitchScale>)

  fun setCirclePitchAlignment(pitchAlignment: CompiledExpression<CirclePitchAlignment>)

  fun setCircleStrokeWidth(strokeWidth: CompiledExpression<DpValue>)

  fun setCircleStrokeColor(strokeColor: CompiledExpression<ColorValue>)

  fun setCircleStrokeOpacity(strokeOpacity: CompiledExpression<FloatValue>)
}
