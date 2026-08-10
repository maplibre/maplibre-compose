package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.CirclePitchAlignment
import org.maplibre.compose.expressions.value.CirclePitchScale
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.Source

internal actual class CircleLayer actual constructor(id: String, source: Source) :
  FeatureLayer(id, source) {

  override val type: String = "circle"

  actual override var sourceLayer: String = ""
    set(value) {
      field = value
      setSourceLayerProperty(value)
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    setFilterExpression(filter)
  }

  actual fun setCircleSortKey(sortKey: CompiledExpression<FloatValue>) {
    setLayoutProperty("circle-sort-key", sortKey)
  }

  actual fun setCircleRadius(radius: CompiledExpression<DpValue>) {
    setPaintProperty("circle-radius", radius)
  }

  actual fun setCircleColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("circle-color", color)
  }

  actual fun setCircleBlur(blur: CompiledExpression<FloatValue>) {
    setPaintProperty("circle-blur", blur)
  }

  actual fun setCircleOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("circle-opacity", opacity)
  }

  actual fun setCircleTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProperty("circle-translate", translate)
  }

  actual fun setCircleTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProperty("circle-translate-anchor", translateAnchor)
  }

  actual fun setCirclePitchScale(pitchScale: CompiledExpression<CirclePitchScale>) {
    setPaintProperty("circle-pitch-scale", pitchScale)
  }

  actual fun setCirclePitchAlignment(pitchAlignment: CompiledExpression<CirclePitchAlignment>) {
    setPaintProperty("circle-pitch-alignment", pitchAlignment)
  }

  actual fun setCircleStrokeWidth(strokeWidth: CompiledExpression<DpValue>) {
    setPaintProperty("circle-stroke-width", strokeWidth)
  }

  actual fun setCircleStrokeColor(strokeColor: CompiledExpression<ColorValue>) {
    setPaintProperty("circle-stroke-color", strokeColor)
  }

  actual fun setCircleStrokeOpacity(strokeOpacity: CompiledExpression<FloatValue>) {
    setPaintProperty("circle-stroke-opacity", strokeOpacity)
  }
}
