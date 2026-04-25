package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.util.toJsonString

internal actual class BackgroundLayer actual constructor(id: String) : Layer() {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val layerId: String = id
  override fun layerType(): String = "background"

  actual fun setBackgroundColor(color: CompiledExpression<ColorValue>) {
    setPaintProp("background-color", color.toJsonString())
  }

  actual fun setBackgroundPattern(pattern: CompiledExpression<ImageValue>) {
    setPaintProp("background-pattern", pattern.toJsonString())
  }

  actual fun setBackgroundOpacity(opacity: CompiledExpression<FloatValue>) {
    setPaintProp("background-opacity", opacity.toJsonString())
  }
}
