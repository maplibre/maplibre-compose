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
import org.maplibre.compose.util.toJsonString
import org.maplibre.kmp.native.style.layers.LineLayer as MLNLineLayer

internal actual class LineLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {

  override val impl = MLNLineLayer(id, source.id)

  actual override var sourceLayer: String
    get() = impl.sourceLayer
    set(value) {
      impl.sourceLayer = value
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    filter.toJsonString()?.let { impl.setFilter(it) }
  }

  actual fun setLineCap(cap: CompiledExpression<LineCap>) {
    cap.toJsonString()?.let { impl.setProperty("line-cap", it) }
  }

  actual fun setLineJoin(join: CompiledExpression<LineJoin>) {
    join.toJsonString()?.let { impl.setProperty("line-join", it) }
  }

  actual fun setLineMiterLimit(miterLimit: CompiledExpression<FloatValue>) {
    miterLimit.toJsonString()?.let { impl.setProperty("line-miter-limit", it) }
  }

  actual fun setLineRoundLimit(roundLimit: CompiledExpression<FloatValue>) {
    roundLimit.toJsonString()?.let { impl.setProperty("line-round-limit", it) }
  }

  actual fun setLineSortKey(sortKey: CompiledExpression<FloatValue>) {
    sortKey.toJsonString()?.let { impl.setProperty("line-sort-key", it) }
  }

  actual fun setLineOpacity(opacity: CompiledExpression<FloatValue>) {
    opacity.toJsonString()?.let { impl.setProperty("line-opacity", it) }
  }

  actual fun setLineColor(color: CompiledExpression<ColorValue>) {
    color.toJsonString()?.let { impl.setProperty("line-color", it) }
  }

  actual fun setLineTranslate(translate: CompiledExpression<DpOffsetValue>) {
    translate.toJsonString()?.let { impl.setProperty("line-translate", it) }
  }

  actual fun setLineTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    translateAnchor.toJsonString()?.let { impl.setProperty("line-translate-anchor", it) }
  }

  actual fun setLineWidth(width: CompiledExpression<DpValue>) {
    width.toJsonString()?.let { impl.setProperty("line-width", it) }
  }

  actual fun setLineGapWidth(gapWidth: CompiledExpression<DpValue>) {
    gapWidth.toJsonString()?.let { impl.setProperty("line-gap-width", it) }
  }

  actual fun setLineOffset(offset: CompiledExpression<DpValue>) {
    offset.toJsonString()?.let { impl.setProperty("line-offset", it) }
  }

  actual fun setLineBlur(blur: CompiledExpression<DpValue>) {
    blur.toJsonString()?.let { impl.setProperty("line-blur", it) }
  }

  actual fun setLineDasharray(dasharray: CompiledExpression<VectorValue<Number>>) {
    dasharray.toJsonString()?.let { impl.setProperty("line-dasharray", it) }
  }

  actual fun setLinePattern(pattern: CompiledExpression<ImageValue>) {
    pattern.toJsonString()?.let { impl.setProperty("line-pattern", it) }
  }

  actual fun setLineGradient(gradient: CompiledExpression<ColorValue>) {
    gradient.toJsonString()?.let { impl.setProperty("line-gradient", it) }
  }
}
