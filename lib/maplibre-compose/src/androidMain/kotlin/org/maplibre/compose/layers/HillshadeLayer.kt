package org.maplibre.compose.layers

import org.maplibre.android.style.layers.HillshadeLayer as MLNHillshadeLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IlluminationAnchor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.toMLNExpression

internal actual class HillshadeLayer actual constructor(id: String, actual val source: Source) :
  Layer() {
  override val impl = MLNHillshadeLayer(id, source.id)

  actual fun setHillshadeIlluminationDirection(direction: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.hillshadeIlluminationDirection(direction.toMLNExpression()))
  }

  actual fun setHillshadeIlluminationAnchor(anchor: CompiledExpression<IlluminationAnchor>) {
    impl.setProperties(PropertyFactory.hillshadeIlluminationAnchor(anchor.toMLNExpression()))
  }

  actual fun setHillshadeExaggeration(exaggeration: CompiledExpression<FloatValue>) {
    impl.setProperties(PropertyFactory.hillshadeExaggeration(exaggeration.toMLNExpression()))
  }

  actual fun setHillshadeShadowColor(shadowColor: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.hillshadeShadowColor(shadowColor.toMLNExpression()))
  }

  actual fun setHillshadeHighlightColor(highlightColor: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.hillshadeHighlightColor(highlightColor.toMLNExpression()))
  }

  actual fun setHillshadeAccentColor(accentColor: CompiledExpression<ColorValue>) {
    impl.setProperties(PropertyFactory.hillshadeAccentColor(accentColor.toMLNExpression()))
  }
}
