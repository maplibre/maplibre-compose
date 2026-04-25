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

internal actual class LineLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val layerId: String = id
  override fun layerType(): String = "line"

  actual override var sourceLayer: String
    get() = sourceLayerString()
    set(value) { updateSourceLayer(value) }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    updateFilter(filter)
  }

  actual fun setLineCap(cap: CompiledExpression<LineCap>) {
    setLayoutProp("line-cap", cap.toJsonString())
  }

  actual fun setLineJoin(join: CompiledExpression<LineJoin>) {
    setLayoutProp("line-join", join.toJsonString())
  }

  actual fun setLineMiterLimit(miterLimit: CompiledExpression<FloatValue>) {
    setLayoutProp("line-miter-limit", miterLimit.toJsonString())
  }

  actual fun setLineRoundLimit(roundLimit: CompiledExpression<FloatValue>) {
    setLayoutProp("line-round-limit", roundLimit.toJsonString())
  }

  actual fun setLineSortKey(sortKey: CompiledExpression<FloatValue>) {
    setLayoutProp("line-sort-key", sortKey.toJsonString())
  }

  actual fun setLineOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProp("line-opacity", opacity.toJsonString())
  }

  actual fun setLineColor(color: CompiledExpression<ColorValue>) {
    setPaintProp("line-color", color.toJsonString())
  }

  actual fun setLineTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProp("line-translate", translate.toJsonString())
  }

  actual fun setLineTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProp("line-translate-anchor", translateAnchor.toJsonString())
  }

  actual fun setLineWidth(width: CompiledExpression<DpValue>) {
    setPaintProp("line-width", width.toJsonString())
  }

  actual fun setLineGapWidth(gapWidth: CompiledExpression<DpValue>) {
    setPaintProp("line-gap-width", gapWidth.toJsonString())
  }

  actual fun setLineOffset(offset: CompiledExpression<DpValue>) {
    setPaintProp("line-offset", offset.toJsonString())
  }

  actual fun setLineBlur(blur: CompiledExpression<DpValue>) {
    setPaintProp("line-blur", blur.toJsonString())
  }

  actual fun setLineDasharray(dasharray: CompiledExpression<VectorValue<Number>>) {
    setPaintProp("line-dasharray", dasharray.toJsonString())
  }

  actual fun setLinePattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProp("line-pattern", pattern.toJsonString())
  }

  actual fun setLineGradient(gradient: CompiledExpression<ColorValue>) {
    setPaintProp("line-gradient", gradient.toJsonString())
  }
}
