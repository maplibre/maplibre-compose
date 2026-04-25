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

internal actual class FillExtrusionLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val layerId: String = id
  override fun layerType(): String = "fill-extrusion"

  actual override var sourceLayer: String
    get() = sourceLayerString()
    set(value) { updateSourceLayer(value) }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    updateFilter(filter)
  }

  actual fun setFillExtrusionOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProp("fill-extrusion-opacity", opacity.toJsonString())
  }

  actual fun setFillExtrusionColor(color: CompiledExpression<ColorValue>) {
    setPaintProp("fill-extrusion-color", color.toJsonString())
  }

  actual fun setFillExtrusionTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProp("fill-extrusion-translate", translate.toJsonString())
  }

  actual fun setFillExtrusionTranslateAnchor(anchor: CompiledExpression<TranslateAnchor>) {
    setPaintProp("fill-extrusion-translate-anchor", anchor.toJsonString())
  }

  actual fun setFillExtrusionPattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProp("fill-extrusion-pattern", pattern.toJsonString())
  }

  actual fun setFillExtrusionHeight(height: CompiledExpression<FloatValue>) {
    setPaintProp("fill-extrusion-height", height.toJsonString())
  }

  actual fun setFillExtrusionBase(base: CompiledExpression<FloatValue>) {
    setPaintProp("fill-extrusion-base", base.toJsonString())
  }

  actual fun setFillExtrusionVerticalGradient(verticalGradient: CompiledExpression<BooleanValue>) {
    setPaintProp("fill-extrusion-vertical-gradient", verticalGradient.toJsonString())
  }
}
