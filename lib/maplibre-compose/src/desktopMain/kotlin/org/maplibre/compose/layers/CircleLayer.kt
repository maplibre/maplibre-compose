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
import org.maplibre.kmp.native.style.layers.CircleLayer as MLNCircleLayer

internal actual class CircleLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = MLNCircleLayer(id, source.id)

  actual override var sourceLayer: String
    get() = impl.sourceLayer
    set(value) {
      impl.sourceLayer = value
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    filter.toJsonString()?.let { impl.setFilter(it) }
  }

  actual fun setCircleSortKey(sortKey: CompiledExpression<FloatValue>) {
    sortKey.toJsonString()?.let { impl.setProperty("circle-sort-key", it) }
  }

  actual fun setCircleRadius(radius: CompiledExpression<DpValue>) {
    radius.toJsonString()?.let { impl.setProperty("circle-radius", it) }
  }

  actual fun setCircleColor(color: CompiledExpression<ColorValue>) {
    color.toJsonString()?.let { impl.setProperty("circle-color", it) }
  }

  actual fun setCircleBlur(blur: CompiledExpression<FloatValue>) {
    blur.toJsonString()?.let { impl.setProperty("circle-blur", it) }
  }

  actual fun setCircleOpacity(opacity: CompiledExpression<FloatValue>) {
    opacity.toJsonString()?.let { impl.setProperty("circle-opacity", it) }
  }

  actual fun setCircleTranslate(translate: CompiledExpression<DpOffsetValue>) {
    translate.toJsonString()?.let { impl.setProperty("circle-translate", it) }
  }

  actual fun setCircleTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    translateAnchor.toJsonString()?.let { impl.setProperty("circle-translate-anchor", it) }
  }

  actual fun setCirclePitchScale(pitchScale: CompiledExpression<CirclePitchScale>) {
    pitchScale.toJsonString()?.let { impl.setProperty("circle-pitch-scale", it) }
  }

  actual fun setCirclePitchAlignment(pitchAlignment: CompiledExpression<CirclePitchAlignment>) {
    pitchAlignment.toJsonString()?.let { impl.setProperty("circle-pitch-alignment", it) }
  }

  actual fun setCircleStrokeWidth(strokeWidth: CompiledExpression<DpValue>) {
    strokeWidth.toJsonString()?.let { impl.setProperty("circle-stroke-width", it) }
  }

  actual fun setCircleStrokeColor(strokeColor: CompiledExpression<ColorValue>) {
    strokeColor.toJsonString()?.let { impl.setProperty("circle-stroke-color", it) }
  }

  actual fun setCircleStrokeOpacity(strokeOpacity: CompiledExpression<FloatValue>) {
    strokeOpacity.toJsonString()?.let { impl.setProperty("circle-stroke-opacity", it) }
  }
}
