package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue

internal actual class BackgroundLayer actual constructor(id: String) : Layer(id) {

  override val type: String = "background"

  actual fun setBackgroundColor(color: CompiledExpression<ColorValue>) {
    setPaintProperty("background-color", color)
  }

  actual fun setBackgroundPattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProperty("background-pattern", pattern)
  }

  actual fun setBackgroundOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProperty("background-opacity", opacity)
  }
}
