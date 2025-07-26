package org.maplibre.compose.layers

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
import org.maplibre.compose.sources.Source

internal actual class LineLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {

  override val impl = TODO()

  actual override var sourceLayer: String = TODO()

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    TODO()
  }

  actual fun setLineCap(cap: CompiledExpression<LineCap>) {
    TODO()
  }

  actual fun setLineJoin(join: CompiledExpression<LineJoin>) {
    TODO()
  }

  actual fun setLineMiterLimit(miterLimit: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setLineRoundLimit(roundLimit: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setLineSortKey(sortKey: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setLineOpacity(opacity: CompiledExpression<FloatValue>) {
    TODO()
  }

  actual fun setLineColor(color: CompiledExpression<ColorValue>) {
    TODO()
  }

  actual fun setLineTranslate(translate: CompiledExpression<DpOffsetValue>) {
    TODO()
  }

  actual fun setLineTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    TODO()
  }

  actual fun setLineWidth(width: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setLineGapWidth(gapWidth: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setLineOffset(offset: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setLineBlur(blur: CompiledExpression<DpValue>) {
    TODO()
  }

  actual fun setLineDasharray(dasharray: CompiledExpression<VectorValue<Number>>) {
    TODO()
  }

  actual fun setLinePattern(pattern: CompiledExpression<ImageValue>) {
    TODO()
  }

  actual fun setLineGradient(gradient: CompiledExpression<ColorValue>) {
    TODO()
  }
}
