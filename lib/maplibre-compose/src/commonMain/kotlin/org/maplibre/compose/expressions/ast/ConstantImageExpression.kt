package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ImageValue

/**
 * Native's location indicator reads images with `asConstant()`: even `["image", name]` aborts
 * rendering. Resolve managed images normally, then expose only a constant image name.
 */
internal data class ConstantImageExpression(val expression: Expression<ImageValue?>?) :
  Expression<ImageValue?> {
  // Image IDs do not change the expression's structure. Probe without registering resources so
  // unsupported expressions can be omitted and reported only after the layer is committed.
  val isSupported: Boolean
    get() = constant(expression?.compile(ProbeContext) ?: NullLiteral) != null

  override fun compile(context: ExpressionContext): CompiledExpression<ImageValue?> =
    (constant(expression?.compile(context) ?: NullLiteral) ?: NullLiteral).cast()

  override fun visit(block: (Expression<*>) -> Unit) {
    block(this)
    expression?.visit(block)
  }

  private fun constant(compiled: CompiledExpression<*>): CompiledExpression<*>? =
    when {
      compiled is StringLiteral || compiled is NullLiteral -> compiled
      compiled is CompiledFunctionCall &&
        compiled.name == "image" &&
        compiled.args.size == 1 &&
        compiled.args[0] is StringLiteral -> compiled.args[0]
      else -> null
    }

  private object ProbeContext : ExpressionContext by ExpressionContext.None {
    override val emScale = const(1f)
    override val spScale = const(1f)
    override val dpScale = const(1f)

    override fun resolveBitmap(bitmap: BitmapLiteral): String = ""

    override fun resolvePainter(painter: PainterLiteral): String = ""
  }
}
