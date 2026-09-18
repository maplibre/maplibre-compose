package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.FloatValue

// Only library-generated conversion arithmetic is eligible for folding.
internal data class UnitConversion(
  val value: Expression<FloatValue>,
  val scale: Expression<FloatValue>,
  val divide: Boolean = false,
) : Expression<FloatValue> {
  override fun compile(context: ExpressionContext): CompiledExpression<FloatValue> {
    val left = value.compile(context)
    val right = scale.compile(context)
    val a = (left as? FloatLiteral)?.value
    val b = (right as? FloatLiteral)?.value
    if (b == 1f) return left
    if (!divide && a == 1f) return right
    if (a != null && b != null && a.isFinite() && b.isFinite() && (!divide || b != 0f)) {
      // The engines calculate with doubles, using the decimal values written to JSON.
      val result =
        if (divide) a.toString().toDouble() / b.toString().toDouble()
        else a.toString().toDouble() * b.toString().toDouble()
      // Kotlin/JS otherwise retains double precision for Float operations.
      val folded = Float.fromBits(result.toFloat().toBits())
      if (folded.isFinite() && folded.toString().toDouble() == result) {
        return FloatLiteral.of(folded)
      }
    }
    return CompiledFunctionCall.of(if (divide) "/" else "*", listOf(left, right)).cast()
  }

  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    value.visit(block)
    scale.visit(block)
  }
}
