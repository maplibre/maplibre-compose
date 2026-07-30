package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.Source

internal actual class FillLayer actual constructor(id: String, source: Source) :
  FeatureLayer(id, source) {

  override val type: String = "fill"

  actual override var sourceLayer: String = ""
    set(value) {
      field = value
      setSourceLayerProperty(value)
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    setFilterExpression(filter)
  }

  actual fun setFillSortKey(sortKey: CompiledExpression<FloatValue>) {
    setLayoutProperty("fill-sort-key", sortKey)
  }

  actual fun setFillAntialias(antialias: CompiledExpression<BooleanValue>) {
    setPaintProperty("fill-antialias", antialias)
  }

  actual fun setFillOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("fill-opacity", opacity)
  }

  actual fun setFillColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("fill-color", color)
  }

  actual fun setFillOutlineColor(outlineColor: CompiledExpression<ColorValue>) {
    setPaintProperty("fill-outline-color", outlineColor)
  }

  actual fun setFillTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProperty("fill-translate", translate)
  }

  actual fun setFillTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProperty("fill-translate-anchor", translateAnchor)
  }

  actual fun setFillPattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProperty("fill-pattern", pattern)
  }
}
