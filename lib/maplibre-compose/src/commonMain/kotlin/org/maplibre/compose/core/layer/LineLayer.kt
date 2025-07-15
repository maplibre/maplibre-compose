package org.maplibre.compose.core.layer

import org.maplibre.compose.core.source.Source
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.expressions.value.VectorValue

internal expect class LineLayer(id: String, source: Source) : FeatureLayer {
  override var sourceLayer: String

  override fun setFilter(filter: CompiledExpression<BooleanValue>)

  fun setLineCap(cap: CompiledExpression<LineCap>)

  fun setLineJoin(join: CompiledExpression<LineJoin>)

  fun setLineMiterLimit(miterLimit: CompiledExpression<FloatValue>)

  fun setLineRoundLimit(roundLimit: CompiledExpression<FloatValue>)

  fun setLineSortKey(sortKey: CompiledExpression<FloatValue>)

  fun setLineOpacity(opacity: CompiledExpression<FloatValue>)

  fun setLineColor(color: CompiledExpression<ColorValue>)

  fun setLineTranslate(translate: CompiledExpression<DpOffsetValue>)

  fun setLineTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>)

  fun setLineWidth(width: CompiledExpression<DpValue>)

  fun setLineGapWidth(gapWidth: CompiledExpression<DpValue>)

  fun setLineOffset(offset: CompiledExpression<DpValue>)

  fun setLineBlur(blur: CompiledExpression<DpValue>)

  fun setLineDasharray(dasharray: CompiledExpression<VectorValue<Number>>)

  fun setLinePattern(pattern: CompiledExpression<ImageValue>)

  fun setLineGradient(gradient: CompiledExpression<ColorValue>)
}
