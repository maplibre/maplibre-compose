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
import org.maplibre.compose.util.toJsonString

internal actual class CircleLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val layerId: String = id
  override fun layerType(): String = "circle"

  actual override var sourceLayer: String
    get() = sourceLayerString()
    set(value) { updateSourceLayer(value) }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    updateFilter(filter)
  }

  actual fun setCircleSortKey(sortKey: CompiledExpression<FloatValue>) {
    setLayoutProp("circle-sort-key", sortKey.toJsonString())
  }

  actual fun setCircleRadius(radius: CompiledExpression<DpValue>) {
    setPaintProp("circle-radius", radius.toJsonString())
  }

  actual fun setCircleColor(color: CompiledExpression<ColorValue>) {
    setPaintProp("circle-color", color.toJsonString())
  }

  actual fun setCircleBlur(blur: CompiledExpression<FloatValue>) {
    setPaintProp("circle-blur", blur.toJsonString())
  }

  actual fun setCircleOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProp("circle-opacity", opacity.toJsonString())
  }

  actual fun setCircleTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProp("circle-translate", translate.toJsonString())
  }

  actual fun setCircleTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    setPaintProp("circle-translate-anchor", translateAnchor.toJsonString())
  }

  actual fun setCirclePitchScale(pitchScale: CompiledExpression<CirclePitchScale>) {
    setPaintProp("circle-pitch-scale", pitchScale.toJsonString())
  }

  actual fun setCirclePitchAlignment(pitchAlignment: CompiledExpression<CirclePitchAlignment>) {
    setPaintProp("circle-pitch-alignment", pitchAlignment.toJsonString())
  }

  actual fun setCircleStrokeWidth(strokeWidth: CompiledExpression<DpValue>) {
    setPaintProp("circle-stroke-width", strokeWidth.toJsonString())
  }

  actual fun setCircleStrokeColor(strokeColor: CompiledExpression<ColorValue>) {
    setPaintProp("circle-stroke-color", strokeColor.toJsonString())
  }

  actual fun setCircleStrokeOpacity(strokeOpacity: CompiledExpression<FloatValue>) {
    setPaintProp("circle-stroke-opacity", strokeOpacity.toJsonString())
  }
}
