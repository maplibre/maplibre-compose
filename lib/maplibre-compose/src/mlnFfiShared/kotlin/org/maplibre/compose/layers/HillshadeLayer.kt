package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IlluminationAnchor
import org.maplibre.compose.sources.Source

internal actual class HillshadeLayer actual constructor(id: String, actual val source: Source) :
  Layer(id) {

  override val type: String = "hillshade"

  override val sourceId: String = source.id

  override val sourceDescriptor: Source
    get() = source

  actual fun setHillshadeIlluminationDirection(direction: CompiledExpression<FloatValue>) {
    setPaintProperty("hillshade-illumination-direction", direction)
  }

  actual fun setHillshadeIlluminationAnchor(anchor: CompiledExpression<IlluminationAnchor>) {
    setPaintProperty("hillshade-illumination-anchor", anchor)
  }

  actual fun setHillshadeExaggeration(exaggeration: CompiledExpression<FloatValue>) {
    setPaintProperty("hillshade-exaggeration", exaggeration)
  }

  actual fun setHillshadeShadowColor(shadowColor: CompiledExpression<ColorValue>) {
    setPaintProperty("hillshade-shadow-color", shadowColor)
  }

  actual fun setHillshadeHighlightColor(highlightColor: CompiledExpression<ColorValue>) {
    setPaintProperty("hillshade-highlight-color", highlightColor)
  }

  actual fun setHillshadeAccentColor(accentColor: CompiledExpression<ColorValue>) {
    setPaintProperty("hillshade-accent-color", accentColor)
  }
}
