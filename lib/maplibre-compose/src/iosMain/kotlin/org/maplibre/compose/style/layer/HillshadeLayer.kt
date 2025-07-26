package org.maplibre.compose.style.layer

import MapLibre.MLNHillshadeStyleLayer
import org.maplibre.compose.style.expressions.ast.CompiledExpression
import org.maplibre.compose.style.expressions.value.ColorValue
import org.maplibre.compose.style.expressions.value.FloatValue
import org.maplibre.compose.style.expressions.value.IlluminationAnchor
import org.maplibre.compose.style.source.Source
import org.maplibre.compose.util.toNSExpression

internal actual class HillshadeLayer actual constructor(id: String, actual val source: Source) :
  Layer() {

  override val impl = MLNHillshadeStyleLayer(id, source.impl)

  actual fun setHillshadeIlluminationDirection(direction: CompiledExpression<FloatValue>) {
    impl.hillshadeIlluminationDirection = direction.toNSExpression()
  }

  actual fun setHillshadeIlluminationAnchor(anchor: CompiledExpression<IlluminationAnchor>) {
    impl.hillshadeIlluminationAnchor = anchor.toNSExpression()
  }

  actual fun setHillshadeExaggeration(exaggeration: CompiledExpression<FloatValue>) {
    impl.hillshadeExaggeration = exaggeration.toNSExpression()
  }

  actual fun setHillshadeShadowColor(shadowColor: CompiledExpression<ColorValue>) {
    impl.hillshadeShadowColor = shadowColor.toNSExpression()
  }

  actual fun setHillshadeHighlightColor(highlightColor: CompiledExpression<ColorValue>) {
    impl.hillshadeHighlightColor = highlightColor.toNSExpression()
  }

  actual fun setHillshadeAccentColor(accentColor: CompiledExpression<ColorValue>) {
    impl.hillshadeAccentColor = accentColor.toNSExpression()
  }
}
