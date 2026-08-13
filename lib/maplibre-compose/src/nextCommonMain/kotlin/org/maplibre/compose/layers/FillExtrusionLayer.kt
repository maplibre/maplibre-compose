package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.Source

internal actual class FillExtrusionLayer actual constructor(id: String, source: Source) :
  FeatureLayer(id, source) {

  override val type: String = "fill-extrusion"

  actual override var sourceLayer: String = ""
    set(value) {
      field = value
      setSourceLayerProperty(value)
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    setFilterExpression(filter)
  }

  actual fun setFillExtrusionOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("fill-extrusion-opacity", opacity)
  }

  actual fun setFillExtrusionColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("fill-extrusion-color", color)
  }

  actual fun setFillExtrusionTranslate(translate: CompiledExpression<DpOffsetValue>) {
    setPaintProperty("fill-extrusion-translate", translate)
  }

  actual fun setFillExtrusionTranslateAnchor(anchor: CompiledExpression<TranslateAnchor>) {
    setPaintProperty("fill-extrusion-translate-anchor", anchor)
  }

  actual fun setFillExtrusionPattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProperty("fill-extrusion-pattern", pattern)
  }

  actual fun setFillExtrusionHeight(height: CompiledExpression<FloatValue>) {
    setPaintProperty("fill-extrusion-height", height)
  }

  actual fun setFillExtrusionBase(base: CompiledExpression<FloatValue>) {
    setPaintProperty("fill-extrusion-base", base)
  }

  actual fun setFillExtrusionRoundedCornerDistance(distance: CompiledExpression<FloatValue>) {
    setLayoutProperty("fill-extrusion-rounded-corner-distance", distance)
  }

  actual fun setFillExtrusionVerticalGradient(verticalGradient: CompiledExpression<BooleanValue>) {
    setPaintProperty("fill-extrusion-vertical-gradient", verticalGradient)
  }
}
