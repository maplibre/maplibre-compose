package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toFfiJsonValue

internal actual class FillLayer actual constructor(id: String, source: Source) :
  FeatureLayer(id, source) {

  override val type: String = "fill"

  // `source-layer` is a root key, and the descriptor only accumulates layout and paint, so this can
  // only be pushed to a live layer.
  // TODO(maplibre-compose): record this in the layer descriptor too, so it is re-emitted when the
  //   layer is added to another style.
  actual override var sourceLayer: String = ""
    set(value) {
      field = value
      mutate { map ->
        map.setLayerProperty(id, "source-layer", JsonPrimitive(value).toFfiJsonValue())
      }
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
