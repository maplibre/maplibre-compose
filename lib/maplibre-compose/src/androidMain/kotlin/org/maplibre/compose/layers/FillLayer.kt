package org.maplibre.compose.layers

import org.maplibre.android.style.expressions.Expression as MLNExpression
import org.maplibre.android.style.layers.FillLayer as MLNFillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.DpOffsetValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toMLNExpression

internal actual class FillLayer actual constructor(id: String, source: Source) :
  FeatureLayer(source) {
  override val impl = MLNFillLayer(id, source.id)

  actual override var sourceLayer: String by impl::sourceLayer

  actual override fun setFilter(filter: CompiledExpression<BooleanValue>) {
    impl.setFilter(filter.toMLNExpression() ?: MLNExpression.literal(true))
  }

  actual fun setFillSortKey(sortKey: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.fillSortKey(sortKey.toMLNExpression()))
  }

  actual fun setFillAntialias(antialias: CompiledExpression<BooleanValue>) {
    impl.setProperties(PropertyFactory.fillAntialias(antialias.toMLNExpression()))
  }

  actual fun setFillOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.fillOpacity(opacity.toMLNExpression()))
  }

  actual fun setFillColor(color: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.fillColor(color.toMLNExpression()))
  }

  actual fun setFillOutlineColor(outlineColor: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.fillOutlineColor(outlineColor.toMLNExpression()))
  }

  actual fun setFillTranslate(translate: CompiledExpression<DpOffsetValue>) {
    impl.setProperties(PropertyFactory.fillTranslate(translate.toMLNExpression()))
  }

  actual fun setFillTranslateAnchor(translateAnchor: CompiledExpression<TranslateAnchor>) {
    impl.setProperties(PropertyFactory.fillTranslateAnchor(translateAnchor.toMLNExpression()))
  }

  actual fun setFillPattern(pattern: CompiledExpression<ImageValue>) {
    impl.setProperties(PropertyFactory.fillPattern(pattern.toMLNExpression()))
  }
}
