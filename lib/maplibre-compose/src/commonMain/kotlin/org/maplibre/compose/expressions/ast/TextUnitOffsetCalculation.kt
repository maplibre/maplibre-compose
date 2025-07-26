package org.maplibre.compose.expressions.ast

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isSpecified
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.value.TextUnitOffsetValue

/**
 * An [Expression] representing a [TextUnitOffsetValue] in EM or SP, which may be transformed into
 * an interpolation function call to convert to the needed units upon compilation.
 */
public data class TextUnitOffsetCalculation private constructor(val x: TextUnit, val y: TextUnit) :
  Expression<TextUnitOffsetValue> {
  override fun compile(context: ExpressionContext): CompiledExpression<TextUnitOffsetValue> {
    val scale =
      when (x.type) {
        TextUnitType.Sp -> context.spScale
        TextUnitType.Em -> context.emScale
        else -> error("Unrecognized TextUnitType: ${x.type}")
      }

    // some reasonably large number to bound the interpolation
    val maxScale = 1000f

    return interpolate(
        type = linear(),
        input = scale,
        0f to offset(0f, 0f),
        1f to offset(x.value, y.value),
        maxScale to offset(x.value * maxScale, y.value * maxScale),
      )
      .compile(context)
      .cast()
  }

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  public companion object {
    public fun of(x: TextUnit, y: TextUnit): TextUnitOffsetCalculation {
      require(x.isSpecified && y.isSpecified) { "TextUnit type must be specified" }
      require(x.type == y.type) { "X and Y text units must have the same type" }
      return TextUnitOffsetCalculation(x, y)
    }
  }
}
