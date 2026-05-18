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
import org.maplibre.kmp.native.style.layers.FillExtrusionLayer as MLNFillExtrusionLayer

internal actual class FillExtrusionLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = MLNFillExtrusionLayer(id, source.id)

  actual override var sourceLayer: String
    get() = impl.sourceLayer
    set(value) {
      impl.sourceLayer = value
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    filter.toJsonString()?.let { impl.setFilter(it) }
  }

  actual fun setFillExtrusionOpacity(opacity: CompiledExpression<FloatValue>) {
    opacity.toJsonString()?.let { impl.setProperty("fill-extrusion-opacity", it) }
  }

  actual fun setFillExtrusionColor(color: CompiledExpression<ColorValue>) {
    color.toJsonString()?.let { impl.setProperty("fill-extrusion-color", it) }
  }

  actual fun setFillExtrusionTranslate(translate: CompiledExpression<DpOffsetValue>) {
    translate.toJsonString()?.let { impl.setProperty("fill-extrusion-translate", it) }
  }

  actual fun setFillExtrusionTranslateAnchor(anchor: CompiledExpression<TranslateAnchor>) {
    anchor.toJsonString()?.let { impl.setProperty("fill-extrusion-translate-anchor", it) }
  }

  actual fun setFillExtrusionPattern(pattern: CompiledExpression<ImageValue>) {
    pattern.toJsonString()?.let { impl.setProperty("fill-extrusion-pattern", it) }
  }

  actual fun setFillExtrusionHeight(height: CompiledExpression<FloatValue>) {
    height.toJsonString()?.let { impl.setProperty("fill-extrusion-height", it) }
  }

  actual fun setFillExtrusionBase(base: CompiledExpression<FloatValue>) {
    base.toJsonString()?.let { impl.setProperty("fill-extrusion-base", it) }
  }

  actual fun setFillExtrusionVerticalGradient(verticalGradient: CompiledExpression<BooleanValue>) {
    verticalGradient.toJsonString()?.let {
      impl.setProperty("fill-extrusion-vertical-gradient", it)
    }
  }
}
