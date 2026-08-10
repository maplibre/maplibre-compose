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
  FeatureLayer(id, source) {

  override val type: String = "line"

  actual override var sourceLayer: String = ""
    set(value) {
      field = value
      setSourceLayerProperty(value)
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    setFilterExpression(filter)
  }

  actual fun setLineCap(cap: CompiledExpression<LineCap>) {
    setLayoutProperty("line-cap", cap)
  }

  actual fun setLineJoin(join: CompiledExpression<LineJoin>) {
    setLayoutProperty("line-join", join)
  }

  actual fun setLineMiterLimit(miterLimit: CompiledExpression<FloatValue>) {
    setLayoutProperty("line-miter-limit", miterLimit)
  }

  actual fun setLineRoundLimit(roundLimit: CompiledExpression<FloatValue>) {
    setLayoutProperty("line-round-limit", roundLimit)
  }

  actual fun setLineSortKey(sortKey: CompiledExpression<FloatValue>) {
    setLayoutProperty("line-sort-key", sortKey)
  }

  actual fun setLineOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("line-opacity", opacity)
  }

  actual fun setLineColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("line-color", color)
  }

  actual fun setLineTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProperty("line-translate", translate)
  }

  actual fun setLineTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProperty("line-translate-anchor", translateAnchor)
  }

  actual fun setLineWidth(width: CompiledExpression<DpValue>) {
    setPaintProperty("line-width", width)
  }

  actual fun setLineGapWidth(gapWidth: CompiledExpression<DpValue>) {
    setPaintProperty("line-gap-width", gapWidth)
  }

  actual fun setLineOffset(offset: CompiledExpression<DpValue>) {
    setPaintProperty("line-offset", offset)
  }

  actual fun setLineBlur(blur: CompiledExpression<DpValue>) {
    setPaintProperty("line-blur", blur)
  }

  actual fun setLineDasharray(dasharray: CompiledExpression<VectorValue<Number>>) {
    setPaintProperty("line-dasharray", dasharray)
  }

  actual fun setLinePattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProperty("line-pattern", pattern)
  }

  actual fun setLineGradient(gradient: CompiledExpression<ColorValue>) {
    setPaintProperty("line-gradient", gradient)
  }
}
