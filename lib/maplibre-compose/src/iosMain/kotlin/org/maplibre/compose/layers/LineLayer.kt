package org.maplibre.compose.layers

import MapLibre.MLNLineStyleLayer
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.NullLiteral
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
import org.maplibre.compose.util.toNSExpression
import org.maplibre.compose.util.toNSPredicate

internal actual class LineLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {

  override val impl = MLNLineStyleLayer(id, source.impl)

  actual override var sourceLayer: String
    get() = impl.sourceLayerIdentifier!!
    set(value) {
      impl.sourceLayerIdentifier = value
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    impl.predicate = filter.toNSPredicate()
  }

  actual fun setLineCap(cap: CompiledExpression<LineCap>) {
    impl.lineCap = cap.toNSExpression()
  }

  actual fun setLineJoin(join: CompiledExpression<LineJoin>) {
    impl.lineJoin = join.toNSExpression()
  }

  actual fun setLineMiterLimit(miterLimit: CompiledExpression<FloatValue>) {
    impl.lineMiterLimit = miterLimit.toNSExpression()
  }

  actual fun setLineRoundLimit(roundLimit: CompiledExpression<FloatValue>) {
    impl.lineRoundLimit = roundLimit.toNSExpression()
  }

  actual fun setLineSortKey(sortKey: CompiledExpression<FloatValue>) {
    impl.lineSortKey = sortKey.toNSExpression()
  }

  actual fun setLineOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.lineOpacity = opacity.toNSExpression()
  }

  actual fun setLineColor(color: CompiledExpression<ColorValue>) {
    impl.lineColor = color.toNSExpression()
  }

  actual fun setLineTranslate(translate: CompiledExpression<DpOffsetValue>) {
    impl.lineTranslation = translate.toNSExpression()
  }

  actual fun setLineTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    impl.lineTranslationAnchor = translateAnchor.toNSExpression()
  }

  actual fun setLineWidth(width: CompiledExpression<DpValue>) {
    impl.lineWidth = width.toNSExpression()
  }

  actual fun setLineGapWidth(gapWidth: CompiledExpression<DpValue>) {
    impl.lineGapWidth = gapWidth.toNSExpression()
  }

  actual fun setLineOffset(offset: CompiledExpression<DpValue>) {
    impl.lineOffset = offset.toNSExpression()
  }

  actual fun setLineBlur(blur: CompiledExpression<DpValue>) {
    impl.lineBlur = blur.toNSExpression()
  }

  actual fun setLineDasharray(dasharray: CompiledExpression<VectorValue<Number>>) {
    impl.lineDashPattern = dasharray.toNSExpression()
  }

  actual fun setLinePattern(pattern: CompiledExpression<ImageValue>) {
    // TODO: figure out how to unset a pattern in iOS
    if (pattern != NullLiteral) {
      impl.linePattern = pattern.toNSExpression()
    }
  }

  actual fun setLineGradient(gradient: CompiledExpression<ColorValue>) {
    impl.lineGradient = gradient.toNSExpression()
  }
}
