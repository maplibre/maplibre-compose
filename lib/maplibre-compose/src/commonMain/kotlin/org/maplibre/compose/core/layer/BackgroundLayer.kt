package org.maplibre.compose.core.layer

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue

internal expect class BackgroundLayer(id: String) : Layer {
  fun setBackgroundColor(color: CompiledExpression<ColorValue>)

  fun setBackgroundPattern(pattern: CompiledExpression<ImageValue>)

  fun setBackgroundOpacity(opacity: CompiledExpression<FloatValue>)
}
