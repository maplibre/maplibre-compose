package org.maplibre.compose.core.layer

import org.maplibre.compose.core.source.Source
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IlluminationAnchor

internal expect class HillshadeLayer(id: String, source: Source) : Layer {
  val source: Source

  fun setHillshadeIlluminationDirection(direction: CompiledExpression<FloatValue>)

  fun setHillshadeIlluminationAnchor(anchor: CompiledExpression<IlluminationAnchor>)

  fun setHillshadeExaggeration(exaggeration: CompiledExpression<FloatValue>)

  fun setHillshadeShadowColor(shadowColor: CompiledExpression<ColorValue>)

  fun setHillshadeHighlightColor(highlightColor: CompiledExpression<ColorValue>)

  fun setHillshadeAccentColor(accentColor: CompiledExpression<ColorValue>)
}
