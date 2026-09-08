package org.maplibre.compose.expressions.ast

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import org.maplibre.compose.expressions.value.TextUnitOffsetValue

internal data class DpTextOffsetCalculation(val x: Dp, val y: Dp) :
  Expression<TextUnitOffsetValue> {
  init {
    require(x.isSpecified && y.isSpecified) { "DP text offset must be specified" }
  }

  override fun compile(context: ExpressionContext): CompiledExpression<TextUnitOffsetValue> =
    scaledTextOffset(x.value, y.value, context.dpScale).compile(context).cast()

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)
}
