package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toJsonString

internal actual class FillLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val layerId: String = id
  override fun layerType(): String = "fill"

  actual override var sourceLayer: String
    get() = sourceLayerString()
    set(value) { updateSourceLayer(value) }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    updateFilter(filter)
  }

  actual fun setFillSortKey(sortKey: CompiledExpression<FloatValue>) {
    setLayoutProp("fill-sort-key", sortKey.toJsonString())
  }

  actual fun setFillAntialias(antialias: CompiledExpression<BooleanValue>) {
    setPaintProp("fill-antialias", antialias.toJsonString())
  }

  actual fun setFillOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProp("fill-opacity", opacity.toJsonString())
  }

  actual fun setFillColor(color: CompiledExpression<ColorValue>) {
    setPaintProp("fill-color", color.toJsonString())
  }

  actual fun setFillOutlineColor(outlineColor: CompiledExpression<ColorValue>) {
    setPaintProp("fill-outline-color", outlineColor.toJsonString())
  }

  actual fun setFillTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProp("fill-translate", translate.toJsonString())
  }

  actual fun setFillTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProp("fill-translate-anchor", translateAnchor.toJsonString())
  }

  actual fun setFillPattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProp("fill-pattern", pattern.toJsonString())
  }
}
