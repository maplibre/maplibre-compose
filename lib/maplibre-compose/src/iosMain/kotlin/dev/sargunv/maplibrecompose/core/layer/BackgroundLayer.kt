package dev.sargunv.maplibrecompose.core.layer

import MapLibre.MLNBackgroundStyleLayer
import dev.sargunv.maplibrecompose.core.util.toNSExpression
import dev.sargunv.maplibrecompose.expressions.ast.CompiledExpression
import dev.sargunv.maplibrecompose.expressions.ast.NullLiteral
import dev.sargunv.maplibrecompose.expressions.value.ColorValue
import dev.sargunv.maplibrecompose.expressions.value.FloatValue
import dev.sargunv.maplibrecompose.expressions.value.ImageValue

internal actual class BackgroundLayer actual constructor(id: String) : Layer() {
  override val impl = MLNBackgroundStyleLayer(id)

  actual fun setBackgroundColor(color: CompiledExpression<ColorValue>) {
    impl.backgroundColor = color.toNSExpression()
  }

  actual fun setBackgroundPattern(pattern: CompiledExpression<ImageValue>) {
    // TODO: figure out how to unset a pattern in iOS
    if (pattern != NullLiteral) {
      impl.backgroundPattern = pattern.toNSExpression()
    }
  }

  actual fun setBackgroundOpacity(opacity: CompiledExpression<FloatValue>) {
    impl.backgroundOpacity = opacity.toNSExpression()
  }
}
