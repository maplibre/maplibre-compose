package org.maplibre.compose.expr.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

internal const val EXPR_FQ_NAME = "org.maplibre.compose.expressions.kotlin.expr"

internal class MapLibreExprIrGenerationExtension(private val messageCollector: MessageCollector) :
  IrGenerationExtension {
  @OptIn(UnsafeDuringIrConstructionAPI::class)
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    moduleFragment.files.forEach { file ->
      file.transform(ExprCallTransformer(pluginContext, file, messageCollector), null)
    }
  }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class ExprCallTransformer(
  private val pluginContext: IrPluginContext,
  private val file: IrFile,
  private val messageCollector: MessageCollector,
) : IrElementTransformerVoid() {
  override fun visitCall(expression: IrCall): IrExpression {
    expression.transformChildrenVoid()
    val ownerName = expression.symbol.owner.fqNameWhenAvailable?.asString()
    if (ownerName != EXPR_FQ_NAME) return expression
    val lambda = expression.arguments.lastOrNull() as? IrFunctionExpression ?: return expression
    val builder =
      pluginContext.irBuiltIns.createIrBuilder(
        expression.symbol,
        expression.startOffset,
        expression.endOffset,
      )
    return ExprIrLowering(pluginContext, builder, file, messageCollector)
      .lowerLambda(lambda.function)
  }
}
