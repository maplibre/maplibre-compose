package org.maplibre.compose.expr.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithoutPatchingParents
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isVararg
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val EMIT_CLASS_ID =
  ClassId(FqName("org.maplibre.compose.expressions.kotlin"), Name.identifier("ExprEmit"))

private val SCOPE_PACKAGES =
  setOf(
    "org.maplibre.compose.expressions.kotlin.ExprScope",
    "org.maplibre.compose.expressions.kotlin.FeatureExpr",
  )

private val MATH_OPS =
  setOf(
    "sqrt",
    "sin",
    "cos",
    "tan",
    "asin",
    "acos",
    "atan",
    "abs",
    "absoluteValue",
    "floor",
    "ceil",
    "round",
    "min",
    "max",
    "ln",
    "log10",
    "log2",
  )

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class ExprIrLowering(
  private val context: IrPluginContext,
  private val builder: IrBuilderWithScope,
) {
  private val emitClass: IrClassSymbol =
    requireNotNull(context.referenceClass(EMIT_CLASS_ID)) {
      "ExprEmit is not on the compilation classpath"
    }

  private val locals = mutableMapOf<IrValueSymbol, IrExpression>()
  private var scopeReceiver: IrValueParameter? = null

  fun lowerLambda(function: IrFunction): IrExpression {
    scopeReceiver =
      function.parameters.firstOrNull { it.kind.name.endsWith("Receiver") }
        ?: function.parameters.firstOrNull()
    val body = function.body ?: error("expr { } lambda has no body")
    return lowerBody(body)
  }

  private fun lowerBody(body: IrBody): IrExpression {
    val statements = body.statementList()
    require(statements.isNotEmpty()) { "expr { } lambda is empty" }
    statements.dropLast(1).forEach(::bindOrIgnore)
    return asExpr(statements.last())
  }

  private fun bindOrIgnore(statement: IrStatement) {
    if (statement is IrVariable) {
      val initializer =
        statement.initializer ?: error("local val ${statement.name} has no initializer")
      locals[statement.symbol] = lowerExpr(initializer)
    } else if (statement is IrExpression) {
      lowerExpr(statement)
    } else {
      error("Unsupported statement in expr { }: ${statement::class.simpleName}")
    }
  }

  private fun asExpr(statement: IrStatement): IrExpression =
    when (statement) {
      is IrVariable -> {
        bindOrIgnore(statement)
        locals.getValue(statement.symbol)
      }
      is IrExpression -> lowerExpr(statement)
      else -> error("expr { } must end with an expression")
    }

  private fun lowerExpr(expression: IrExpression): IrExpression =
    when (expression) {
      is IrReturn -> lowerExpr(expression.value)
      is IrBlock -> {
        val statements = expression.statements
        require(statements.isNotEmpty()) { "empty block in expr { }" }
        statements.dropLast(1).forEach(::bindOrIgnore)
        asExpr(statements.last())
      }
      is IrWhen -> lowerWhen(expression)
      is IrCall -> lowerCall(expression)
      is IrGetValue -> lowerGetValue(expression)
      is IrStringConcatenation -> emitOp("concat", expression.arguments.map(::lowerExpr))
      is IrTypeOperatorCall -> lowerExpr(expression.argument)
      is IrConst,
      is IrConstructorCall,
      is IrGetObjectValue,
      is IrGetEnumValue,
      is IrGetField -> emitLit(expression)
      is IrFunctionExpression -> error("Nested lambdas in expr { } are only supported via bind { }")
      else -> emitLit(expression)
    }

  private fun lowerGetValue(expression: IrGetValue): IrExpression {
    locals[expression.symbol]?.let {
      return it
    }
    if (expression.symbol == scopeReceiver?.symbol) {
      error("The expr { } receiver is not a value; use feature, zoom, or a helper")
    }
    return emitLit(expression)
  }

  private fun lowerWhen(expression: IrWhen): IrExpression {
    when (expression.origin) {
      IrStatementOrigin.ANDAND -> {
        val first = expression.branches.first()
        return emitOp("all", listOf(lowerExpr(first.condition), lowerExpr(first.result)))
      }
      IrStatementOrigin.OROR -> {
        val first = expression.branches.first()
        return emitOp(
          "any",
          listOf(lowerExpr(first.condition), lowerExpr(expression.branches.last().result)),
        )
      }
      IrStatementOrigin.ELVIS -> {
        val first = expression.branches.first()
        return emitOp(
          "coalesce",
          listOf(lowerExpr(first.result), lowerExpr(expression.branches.last().result)),
        )
      }
      else -> Unit
    }
    val tests = expression.branches.filterNot { it.isElseBranch() }
    val fallback =
      expression.branches.firstOrNull { it.isElseBranch() }?.let { lowerExpr(it.result) }
        ?: emitLitNull()
    val args = buildList {
      for (branch in tests) {
        add(lowerExpr(branch.condition))
        add(lowerExpr(branch.result))
      }
      add(fallback)
    }
    return emitOp("case", args)
  }

  private fun IrBranch.isElseBranch(): Boolean {
    val condition = condition
    return condition is IrConst && condition.value == true
  }

  private fun lowerCall(expression: IrCall): IrExpression {
    val owner = expression.symbol.owner
    val name = owner.name.asString()
    val parentFq = owner.parentFqName()
    val callableFq = owner.fqNameWhenAvailable?.asString().orEmpty()

    if (
      parentFq in SCOPE_PACKAGES ||
        callableFq.startsWith("org.maplibre.compose.expressions.kotlin.")
    ) {
      return lowerScopeCall(name, expression)
    }
    operatorLowering(name, expression)?.let {
      return it
    }
    stdlibLowering(name, expression)?.let {
      return it
    }
    return emitLit(expression)
  }

  private fun lowerScopeCall(name: String, expression: IrCall): IrExpression {
    val args = valueArgs(expression)
    return when (name) {
      "getFeature",
      "<get-feature>" -> error("Use feature[\"key\"] or feature.number(\"key\")")
      "getZoom",
      "<get-zoom>" -> emitOp("zoom")
      "getHeatmapDensity",
      "<get-heatmapDensity>" -> emitOp("heatmap-density")
      "getElevation",
      "<get-elevation>" -> emitOp("elevation")
      "linear" -> emitOp("linear")
      "exponential" -> emitOp("exponential", args.map(::lowerExpr))
      "cubicBezier" -> emitOp("cubic-bezier", args.map(::lowerExpr))
      "interpolate" -> emitInterpolate("interpolate", args)
      "interpolateHcl" -> emitInterpolate("interpolate-hcl", args)
      "interpolateLab" -> emitInterpolate("interpolate-lab", args)
      "step" -> emitStep(args)
      "rgb" -> emitOp(if (args.size >= 4) "rgba" else "rgb", args.map(::lowerExpr))
      "toRgba" -> emitOp("to-rgba", listOf(lowerReceiver(expression)))
      "format" -> emitFormat(args)
      "span" -> args.firstOrNull()?.let(::lowerExpr) ?: emitLitNull()
      "image" -> emitOp("image", args.map(::lowerExpr))
      "bind" -> emitBind(args)
      "asNumber" -> emitOp("number", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "asString" -> emitOp("string", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "asBoolean" -> emitOp("boolean", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "asColor",
      "convertToColor" ->
        emitOp("to-color", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "asMap" -> emitOp("object", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "asList" -> emitOp("array", listOf(lowerReceiver(expression)))
      "convertToNumber" ->
        emitOp("to-number", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "convertToString" -> emitOp("to-string", listOf(lowerReceiver(expression)))
      "convertToBoolean" -> emitOp("to-boolean", listOf(lowerReceiver(expression)))
      "typeOf" -> emitOp("typeof", listOf(lowerReceiver(expression)))
      "collator" -> emitCollator(args)
      "formatToString" -> emitNumberFormat(expression, args)
      "offset" -> emitLit(expression)
      "dpOffset" -> emitLit(expression)
      "padding" -> emitLit(expression)
      "textVariableAnchorOffset" -> emitOp("literal", args.flatMap(::flattenPair))
      "get" -> emitOp("get", args.map(::lowerExpr))
      "has" -> emitOp("has", args.map(::lowerExpr))
      "properties" -> emitOp("properties")
      "state" -> emitOp("feature-state", args.map(::lowerExpr))
      "geometryType" -> emitOp("geometry-type")
      "id" -> emitOp("id")
      "lineProgress" -> emitOp("line-progress")
      "accumulated" -> emitOp("accumulated")
      "within" -> emitOp("within", args.map(::lowerExpr))
      "distance" -> emitOp("distance", args.map(::lowerExpr))
      "number" -> emitOp("number", listOf(emitOp("get", args.map(::lowerExpr))))
      "string" -> emitOp("string", listOf(emitOp("get", args.map(::lowerExpr))))
      "boolean" -> emitOp("boolean", listOf(emitOp("get", args.map(::lowerExpr))))
      else -> error("Unsupported expr helper $name")
    }
  }

  private fun operatorLowering(name: String, expression: IrCall): IrExpression? {
    val operands = allOperands(expression)
    if (
      name == "compareTo" ||
        name == "greater" ||
        name == "less" ||
        name == "greaterOrEqual" ||
        name == "lessOrEqual"
    ) {
      val op =
        when (expression.origin) {
          IrStatementOrigin.GT -> ">"
          IrStatementOrigin.GTEQ -> ">="
          IrStatementOrigin.LT -> "<"
          IrStatementOrigin.LTEQ -> "<="
          IrStatementOrigin.EQEQ,
          IrStatementOrigin.EQEQEQ -> "=="
          else ->
            when (name) {
              "greater" -> ">"
              "greaterOrEqual" -> ">="
              "less" -> "<"
              "lessOrEqual" -> "<="
              else -> return null
            }
        }
      return emitOp(op, operands.map(::lowerExpr))
    }
    val op =
      when (name) {
        "plus" -> if (expression.type.toString().contains("String")) "concat" else "+"
        "minus" -> "-"
        "times" -> "*"
        "div" -> "/"
        "rem" -> "%"
        "unaryMinus" -> "-"
        "not" -> "!"
        "EQEQ",
        "EQEQEQ",
        "ieee754equals",
        "equals" -> "=="
        "ENEQ",
        "ENEQEQ" -> "!="
        "and" -> "all"
        "or" -> "any"
        "contains" ->
          return emitOp("in", listOf(lowerExpr(operands.last()), lowerExpr(operands.first())))
        else -> return null
      }
    return emitOp(op, operands.map(::lowerExpr))
  }

  private fun stdlibLowering(name: String, expression: IrCall): IrExpression? {
    val operands = allOperands(expression)
    when (name) {
      "pow" -> return emitOp("^", operands.map(::lowerExpr))
      "toDouble",
      "toFloat",
      "toInt",
      "toLong" -> return operands.firstOrNull()?.let(::lowerExpr) ?: emitLit(expression)
      "<get-dp>",
      "getDp",
      "<get-sp>",
      "getSp",
      "<get-em>",
      "getEm" -> return lowerReceiverOrLit(expression)
      "uppercase",
      "uppercaseChar" -> return emitOp("upcase", listOf(lowerReceiverOrFirst(expression, operands)))
      "lowercase",
      "lowercaseChar" ->
        return emitOp("downcase", listOf(lowerReceiverOrFirst(expression, operands)))
      "substring",
      "subList",
      "slice" -> return emitOp("slice", operands.map(::lowerExpr))
      "split" -> return emitOp("split", operands.map(::lowerExpr))
      "length",
      "<get-length>",
      "<get-size>",
      "count" -> return emitOp("length", listOf(lowerReceiverOrFirst(expression, operands)))
      "indexOf" -> {
        val receiver = operands.first()
        val needle = operands[1]
        val rest = operands.drop(2)
        return emitOp(
          "index-of",
          listOf(lowerExpr(needle), lowerExpr(receiver)) + rest.map(::lowerExpr),
        )
      }
      "joinToString" -> {
        val receiver = operands.first()
        val sep = operands.getOrNull(1)
        return emitOp("join", listOf(lowerExpr(receiver)) + listOfNotNull(sep?.let(::lowerExpr)))
      }
      "get" -> {
        if (operands.size >= 2) {
          return emitOp("at", listOf(lowerExpr(operands.last()), lowerExpr(operands.first())))
        }
      }
    }
    if (name in MATH_OPS) {
      val mapped = if (name == "absoluteValue") "abs" else name
      return emitOp(mapped, operands.map(::lowerExpr))
    }
    return null
  }

  private fun emitInterpolate(kind: String, args: List<IrExpression>): IrExpression {
    require(args.size >= 2) { "$kind needs a type and an input" }
    val type = lowerExpr(args[0])
    val input = lowerExpr(args[1])
    val stops = args.drop(2).flatMap(::flattenPair)
    return emitNamedVararg("interpolate", listOf(builder.irString(kind), type, input), stops)
  }

  private fun emitStep(args: List<IrExpression>): IrExpression {
    require(args.size >= 2) { "step needs an input and a fallback" }
    val input = lowerExpr(args[0])
    val fallback = lowerExpr(args[1])
    val stops = args.drop(2).flatMap(::flattenPair)
    return emitNamedVararg("step", listOf(input, fallback), stops)
  }

  private fun emitFormat(args: List<IrExpression>): IrExpression {
    val pieces = args.flatMap { listOf(lowerExpr(it), emitSpanOptions()) }
    return emitNamedVararg("formatSpans", emptyList(), pieces)
  }

  private fun emitSpanOptions(): IrExpression {
    val fn = emitFunction("spanOptions")
    return builder.irCall(fn).apply {
      arguments[0] = builder.irGetObject(emitClass)
      arguments[1] = builder.irNull()
      arguments[2] = builder.irNull()
      arguments[3] = builder.irNull()
    }
  }

  private fun emitBind(args: List<IrExpression>): IrExpression {
    require(args.size >= 3) { "bind(name, value) { ... }" }
    val name = lowerExpr(args[0])
    val value = lowerExpr(args[1])
    val bodyLambda = args[2] as? IrFunctionExpression ?: error("bind body must be a lambda")
    val param = bodyLambda.function.parameters.last()
    locals[param.symbol] = emitOp("var", listOf(name))
    val body = lowerBody(bodyLambda.function.body ?: error("bind lambda has no body"))
    return emitOp("let", listOf(name, value, body))
  }

  private fun emitCollator(args: List<IrExpression>): IrExpression {
    val keys = listOf("case-sensitive", "diacritic-sensitive", "locale")
    val optionArgs = buildList {
      args.forEachIndexed { index, arg ->
        if (index < keys.size) {
          add(builder.irString(keys[index]))
          add(lowerExpr(arg))
        }
      }
    }
    return emitOp("collator", listOf(emitNamedVararg("namedOptions", emptyList(), optionArgs)))
  }

  private fun emitNumberFormat(expression: IrCall, args: List<IrExpression>): IrExpression {
    val keys = listOf("locale", "currency", "min-fraction-digits", "max-fraction-digits")
    val optionArgs = buildList {
      args.forEachIndexed { index, arg ->
        if (index < keys.size) {
          add(builder.irString(keys[index]))
          add(lowerExpr(arg))
        }
      }
    }
    return emitOp(
      "number-format",
      listOf(lowerReceiver(expression), emitNamedVararg("namedOptions", emptyList(), optionArgs)),
    )
  }

  private fun flattenPair(expression: IrExpression): List<IrExpression> {
    if (expression is IrVararg) {
      return expression.elements.flatMap { element ->
        when (element) {
          is IrExpression -> flattenPair(element)
          is IrSpreadElement -> flattenPair(element.expression)
          else -> error("Unsupported vararg element")
        }
      }
    }
    if (expression is IrCall && expression.symbol.owner.name.asString() == "to") {
      return allOperands(expression).map(::lowerExpr)
    }
    if (
      expression is IrConstructorCall &&
        expression.symbol.owner.parentAsClass.fqNameWhenAvailable?.asString() == "kotlin.Pair"
    ) {
      return expression.arguments.filterNotNull().map(::lowerExpr)
    }
    return listOf(lowerExpr(expression))
  }

  private fun valueArgs(expression: IrCall): List<IrExpression> {
    val owner = expression.symbol.owner
    val result = mutableListOf<IrExpression>()
    owner.parameters.forEachIndexed { index, parameter ->
      if (parameter.kind.name.endsWith("Receiver") || parameter.kind.name == "Context")
        return@forEachIndexed
      val arg = expression.arguments.getOrNull(index) ?: return@forEachIndexed
      if (parameter.isVararg && arg is IrVararg) {
        arg.elements.forEach { element ->
          when (element) {
            is IrExpression -> result += element
            is IrSpreadElement -> result += element.expression
          }
        }
      } else {
        result += arg
      }
    }
    return result
  }

  private fun allOperands(expression: IrCall): List<IrExpression> {
    val owner = expression.symbol.owner
    val result = mutableListOf<IrExpression>()
    owner.parameters.forEachIndexed { index, parameter ->
      if (parameter.kind.name == "Context") return@forEachIndexed
      val arg = expression.arguments.getOrNull(index) ?: return@forEachIndexed
      if (isScopeReceiver(arg)) return@forEachIndexed
      if (parameter.isVararg && arg is IrVararg) {
        arg.elements.forEach { element -> if (element is IrExpression) result += element }
      } else {
        result += arg
      }
    }
    return result
  }

  private fun lowerReceiver(expression: IrCall): IrExpression {
    val owner = expression.symbol.owner
    owner.parameters.forEachIndexed { index, parameter ->
      if (parameter.kind.name != "ExtensionReceiver") return@forEachIndexed
      val arg = expression.arguments[index]
      if (arg != null && !isScopeReceiver(arg)) return lowerExpr(arg)
    }
    owner.parameters.forEachIndexed { index, parameter ->
      if (parameter.kind.name != "DispatchReceiver") return@forEachIndexed
      val arg = expression.arguments[index]
      if (arg != null && !isScopeReceiver(arg)) return lowerExpr(arg)
    }
    return valueArgs(expression).firstOrNull()?.let(::lowerExpr)
      ?: error("Call ${expression.symbol.owner.name} has no receiver")
  }

  private fun lowerReceiverOrLit(expression: IrCall): IrExpression =
    try {
      lowerReceiver(expression)
    } catch (_: IllegalStateException) {
      emitLit(expression)
    }

  private fun lowerReceiverOrFirst(expression: IrCall, operands: List<IrExpression>): IrExpression =
    try {
      lowerReceiver(expression)
    } catch (_: IllegalStateException) {
      operands.firstOrNull()?.let(::lowerExpr) ?: emitLit(expression)
    }

  private fun isScopeReceiver(expression: IrExpression?): Boolean {
    val get = expression as? IrGetValue ?: return false
    return get.symbol == scopeReceiver?.symbol
  }

  private fun emitOp(name: String, args: List<IrExpression> = emptyList()): IrExpression {
    val fn = emitFunction("op")
    return builder.irCall(fn).apply {
      arguments[0] = builder.irGetObject(emitClass)
      arguments[1] = builder.irString(name)
      arguments[2] = builder.irVararg(context.irBuiltIns.anyNType, args)
    }
  }

  private fun emitLit(value: IrExpression): IrExpression {
    val fn = emitFunction("lit")
    return builder.irCall(fn).apply {
      arguments[0] = builder.irGetObject(emitClass)
      arguments[1] = value.deepCopyWithoutPatchingParents()
    }
  }

  private fun emitLitNull(): IrExpression = emitLit(builder.irNull())

  private fun emitNamedVararg(
    functionName: String,
    prefix: List<IrExpression>,
    varargArgs: List<IrExpression>,
  ): IrExpression {
    val fn = emitFunction(functionName)
    return builder.irCall(fn).apply {
      var slot = 0
      arguments[slot++] = builder.irGetObject(emitClass)
      prefix.forEach { arguments[slot++] = it }
      arguments[slot] = builder.irVararg(context.irBuiltIns.anyNType, varargArgs)
    }
  }

  private fun emitFunction(name: String): IrSimpleFunctionSymbol {
    val id = CallableId(EMIT_CLASS_ID, Name.identifier(name))
    return context.referenceFunctions(id).singleOrNull() ?: error("ExprEmit.$name not found")
  }
}

private fun IrDeclarationWithName.parentFqName(): String =
  when (val parent = parent) {
    is IrClass -> parent.fqNameWhenAvailable?.asString().orEmpty()
    is IrPackageFragment -> parent.packageFqName.asString()
    is IrDeclarationWithName -> parent.fqNameWhenAvailable?.asString().orEmpty()
    else -> ""
  }

private fun IrBody.statementList(): List<IrStatement> =
  when (this) {
    is IrBlockBody -> statements
    is IrExpressionBody -> listOf(expression)
    else -> error("Unsupported body ${this::class.simpleName}")
  }
