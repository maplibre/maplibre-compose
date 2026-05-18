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
import org.maplibre.kmp.native.style.layers.FillLayer as MLNFillLayer

internal actual class FillLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {

  override val impl = MLNFillLayer(id, source.id)

  actual override var sourceLayer: String
    get() = impl.sourceLayer
    set(value) {
      impl.sourceLayer = value
    }

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    filter.toJsonString()?.let { impl.setFilter(it) }
  }

  actual fun setFillSortKey(sortKey: CompiledExpression<FloatValue>) {
    sortKey.toJsonString()?.let { impl.setProperty("fill-sort-key", it) }
  }

  actual fun setFillAntialias(antialias: CompiledExpression<BooleanValue>) {
    antialias.toJsonString()?.let { impl.setProperty("fill-antialias", it) }
  }

  actual fun setFillOpacity(opacity: CompiledExpression<FloatValue>) {
    opacity.toJsonString()?.let { impl.setProperty("fill-opacity", it) }
  }

  actual fun setFillColor(color: CompiledExpression<ColorValue>) {
    color.toJsonString()?.let { impl.setProperty("fill-color", it) }
  }

  actual fun setFillOutlineColor(outlineColor: CompiledExpression<ColorValue>) {
    outlineColor.toJsonString()?.let { impl.setProperty("fill-outline-color", it) }
  }

  actual fun setFillTranslate(translate: CompiledExpression<DpOffsetValue>) {
    translate.toJsonString()?.let { impl.setProperty("fill-translate", it) }
  }

  actual fun setFillTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    translateAnchor.toJsonString()?.let { impl.setProperty("fill-translate-anchor", it) }
  }

  actual fun setFillPattern(pattern: CompiledExpression<ImageValue>) {
    pattern.toJsonString()?.let { impl.setProperty("fill-pattern", it) }
  }
}
