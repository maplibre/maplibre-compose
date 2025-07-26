package org.maplibre.compose.layers

import org.maplibre.android.style.expressions.Expression as MLNExpression
import org.maplibre.android.style.layers.LineLayer as MLNLineLayer
import org.maplibre.android.style.layers.PropertyFactory
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
import org.maplibre.compose.util.toMLNExpression

internal actual class LineLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {

  override val impl = MLNLineLayer(id, source.id)

  actual override var sourceLayer: String by impl::sourceLayer

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    impl.setFilter(filter.toMLNExpression() ?: MLNExpression.literal(true))
  }

  actual fun setLineCap(cap: CompiledExpression<LineCap>) {
    impl.setProperties(PropertyFactory.lineCap(cap.toMLNExpression()))
  }

  actual fun setLineJoin(join: CompiledExpression<LineJoin>) {
    impl.setProperties(PropertyFactory.lineJoin(join.toMLNExpression()))
  }

  actual fun setLineMiterLimit(miterLimit: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.lineMiterLimit(miterLimit.toMLNExpression()))
  }

  actual fun setLineRoundLimit(roundLimit: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.lineRoundLimit(roundLimit.toMLNExpression()))
  }

  actual fun setLineSortKey(sortKey: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.lineSortKey(sortKey.toMLNExpression()))
  }

  actual fun setLineOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.lineOpacity(opacity.toMLNExpression()))
  }

  actual fun setLineColor(color: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.lineColor(color.toMLNExpression()))
  }

  actual fun setLineTranslate(translate: CompiledExpression<DpOffsetValue>) {
    impl.setProperties(PropertyFactory.lineTranslate(translate.toMLNExpression()))
  }

  actual fun setLineTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    impl.setProperties(PropertyFactory.lineTranslateAnchor(translateAnchor.toMLNExpression()))
  }

  actual fun setLineWidth(width: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.lineWidth(width.toMLNExpression()))
  }

  actual fun setLineGapWidth(gapWidth: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.lineGapWidth(gapWidth.toMLNExpression()))
  }

  actual fun setLineOffset(offset: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.lineOffset(offset.toMLNExpression()))
  }

  actual fun setLineBlur(blur: CompiledExpression<DpValue>) {
    impl.setProperties(PropertyFactory.lineBlur(blur.toMLNExpression()))
  }

  actual fun setLineDasharray(dasharray: CompiledExpression<VectorValue<Number>>) {
    impl.setProperties(PropertyFactory.lineDasharray(dasharray.toMLNExpression()))
  }

  actual fun setLinePattern(pattern: CompiledExpression<ImageValue>) {
    impl.setProperties(PropertyFactory.linePattern(pattern.toMLNExpression()))
  }

  actual fun setLineGradient(gradient: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.lineGradient(gradient.toMLNExpression()))
  }
}
