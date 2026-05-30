package org.maplibre.compose.layers

import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.util.toJsonString
import org.maplibre.kmp.native.style.layers.BackgroundLayer as MLNBackgroundLayer

internal actual class BackgroundLayer actual constructor(id: String) : Layer() {

  override val impl = MLNBackgroundLayer(id)

  actual fun setBackgroundColor(color: CompiledExpression<ColorValue>) {
    color.toJsonString()?.let { impl.setProperty("background-color", it) }
  }

  actual fun setBackgroundPattern(pattern: CompiledExpression<ImageValue>) {
    pattern.toJsonString()?.let { impl.setProperty("background-pattern", it) }
  }

  actual fun setBackgroundOpacity(opacity: CompiledExpression<FloatValue>) {
    opacity.toJsonString()?.let { impl.setProperty("background-opacity", it) }
  }
}
