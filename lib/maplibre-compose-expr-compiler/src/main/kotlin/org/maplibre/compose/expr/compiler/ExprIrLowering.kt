package org.maplibre.compose.expr.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFile
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
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
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

private val COMPOSE_ONLY_HELPERS =
  setOf("offset", "dpOffset", "padding", "projectionTransition", "textVariableAnchorOffset")

private val UNIT_IDENTITY = setOf("<get-dp>", "getDp", "<get-milliseconds>", "getMilliseconds")
private val UNIT_SECONDS = setOf("<get-seconds>", "getSeconds")
private val UNIT_SP = setOf("<get-sp>", "getSp")
private val UNIT_EM = setOf("<get-em>", "getEm")

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class ExprIrLowering(
  private val context: IrPluginContext,
  private val builder: IrBuilderWithScope,
  private val file: IrFile,
  private val messageCollector: MessageCollector,
) {
  private val emitClass: IrClassSymbol =
    requireNotNull(context.referenceClass(EMIT_CLASS_ID)) {
      "ExprEmit is not on the compilation classpath"
    }

  private val locals = mutableMapOf<IrValueSymbol, IrExpression>()
  private val mapLocals = mutableSetOf<IrValueSymbol>()
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
    if (statements.isEmpty()) {
      report(body, ExprDiagnostics.emptyBlock())
      return emitLitNull()
    }
    statements.dropLast(1).forEach(::bindOrIgnore)
    return asExpr(statements.last())
  }

  private fun bindOrIgnore(statement: IrStatement) {
    when (statement) {
      is IrVariable -> bindVariable(statement)
      is IrLoop -> report(statement, ExprDiagnostics.loopNotAllowed())
      is IrTry -> report(statement, ExprDiagnostics.tryNotAllowed())
      is IrSetValue -> report(statement, ExprDiagnostics.assignmentNotAllowed())
      is IrExpression -> lowerExpr(statement)
      else ->
        report(statement, ExprDiagnostics.unsupportedStatement(statement::class.simpleName ?: "?"))
    }
  }

  private fun bindVariable(statement: IrVariable) {
    if (statement.isVar) {
      report(statement, ExprDiagnostics.varNotAllowed())
    }
    val initializer = statement.initializer
    if (initializer == null) {
      report(
        statement,
        ExprDiagnostics.unsupportedStatement("val ${statement.name} without initializer"),
      )
      locals[statement.symbol] = emitLitNull()
      return
    }
    if (isMapEvaluated(initializer)) mapLocals += statement.symbol
    locals[statement.symbol] = lowerExpr(initializer)
  }

  private fun asExpr(statement: IrStatement): IrExpression =
    when (statement) {
      is IrVariable -> {
        bindOrIgnore(statement)
        locals[statement.symbol] ?: emitLitNull()
      }
      is IrExpression -> lowerExpr(statement)
      else -> {
        report(statement, ExprDiagnostics.unsupportedStatement(statement::class.simpleName ?: "?"))
        emitLitNull()
      }
    }

  private fun lowerExpr(expression: IrExpression): IrExpression =
    when (expression) {
      is IrReturn -> lowerExpr(expression.value)
      is IrBlock -> {
        val statements = expression.statements
        if (statements.isEmpty()) {
          report(expression, ExprDiagnostics.emptyBlock())
          emitLitNull()
        } else {
          statements.dropLast(1).forEach(::bindOrIgnore)
          asExpr(statements.last())
        }
      }
      is IrWhen -> lowerWhen(expression)
      is IrCall -> lowerCall(expression)
      is IrGetValue -> lowerGetValue(expression)
      is IrStringConcatenation -> emitOp("concat", expression.arguments.map(::lowerExpr))
      is IrTypeOperatorCall -> lowerExpr(expression.argument)
      is IrLoop -> {
        report(expression, ExprDiagnostics.loopNotAllowed())
        emitLitNull()
      }
      is IrTry -> {
        report(expression, ExprDiagnostics.tryNotAllowed())
        emitLitNull()
      }
      is IrSetValue -> {
        report(expression, ExprDiagnostics.assignmentNotAllowed())
        emitLitNull()
      }
      is IrConst,
      is IrConstructorCall,
      is IrGetObjectValue,
      is IrGetEnumValue,
      is IrGetField -> emitCapture(expression)
      is IrFunctionExpression -> {
        report(expression, ExprDiagnostics.nestedLambda())
        emitLitNull()
      }
      else -> emitCapture(expression)
    }

  private fun lowerGetValue(expression: IrGetValue): IrExpression {
    locals[expression.symbol]?.let {
      return it
    }
    if (expression.symbol == scopeReceiver?.symbol) {
      report(expression, ExprDiagnostics.receiverIsNotAValue())
      return emitLitNull()
    }
    return emitCapture(expression)
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
    matchFromEqualityWhen(expression)?.let {
      return it
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

  private fun matchFromEqualityWhen(expression: IrWhen): IrExpression? {
    val tests = expression.branches.filterNot { it.isElseBranch() }
    if (tests.isEmpty()) return null
    data class Eq(val subject: IrExpression, val label: IrExpression, val result: IrExpression)
    val eqs = tests.map { branch ->
      val eq = asEquality(branch.condition) ?: return null
      Eq(eq.first, eq.second, branch.result)
    }
    if (!eqs.all { sameSubject(eqs[0].subject, it.subject) }) return null
    val fallback =
      expression.branches.firstOrNull { it.isElseBranch() }?.let { lowerExpr(it.result) }
        ?: emitLitNull()
    val labelsAndOutputs = eqs.flatMap { listOf(lowerExpr(it.label), lowerExpr(it.result)) }
    return emitNamedVararg("match", listOf(lowerExpr(eqs[0].subject), fallback), labelsAndOutputs)
  }

  private fun asEquality(condition: IrExpression): Pair<IrExpression, IrExpression>? {
    val call = condition as? IrCall ?: return null
    val name = call.symbol.owner.name.asString()
    val isEq =
      call.origin == IrStatementOrigin.EQEQ ||
        call.origin == IrStatementOrigin.EQEQEQ ||
        name == "equals" ||
        name == "EQEQ" ||
        name == "ieee754equals"
    if (!isEq) return null
    val ops = allOperands(call)
    if (ops.size != 2) return null
    val (a, b) = ops
    return when {
      !isMapEvaluated(b) -> a to b
      !isMapEvaluated(a) -> b to a
      else -> null
    }
  }

  private fun sameSubject(a: IrExpression, b: IrExpression): Boolean {
    val ga = a as? IrGetValue
    val gb = b as? IrGetValue
    if (ga != null && gb != null) return ga.symbol == gb.symbol
    val ca = a as? IrCall
    val cb = b as? IrCall
    if (ca != null && cb != null) {
      if (ca.symbol != cb.symbol) return false
      val ao = allOperands(ca)
      val bo = allOperands(cb)
      if (ao.size != bo.size) return false
      return ao.zip(bo).all { (left, right) -> sameSubject(left, right) }
    }
    val la = a as? IrConst
    val lb = b as? IrConst
    if (la != null && lb != null) return la.value == lb.value
    return false
  }

  private fun IrBranch.isElseBranch(): Boolean {
    val condition = condition
    return condition is IrConst && condition.value == true
  }

  private fun lowerCall(expression: IrCall): IrExpression {
    val owner = expression.symbol.owner
    val name = owner.name.asString()
    val parentFq = owner.parentFqName()

    if (parentFq in SCOPE_PACKAGES) {
      return lowerScopeCall(name, expression)
    }
    operatorLowering(name, expression)?.let {
      return it
    }
    stdlibLowering(name, expression)?.let {
      return it
    }
    return emitCapture(expression)
  }

  private fun lowerScopeCall(name: String, expression: IrCall): IrExpression {
    val args = valueArgs(expression)
    return when (name) {
      "getFeature",
      "<get-feature>" -> {
        report(expression, ExprDiagnostics.receiverIsNotAValue())
        emitLitNull()
      }
      "getZoom",
      "<get-zoom>" -> emitOp("zoom")
      "getHeatmapDensity",
      "<get-heatmapDensity>" -> emitOp("heatmap-density")
      "getElevation",
      "<get-elevation>" -> emitOp("elevation")
      "getLn2",
      "<get-ln2>" -> emitOp("ln2")
      "getPi",
      "<get-pi>" -> emitOp("pi")
      "getE",
      "<get-e>" -> emitOp("e")
      "linear" -> emitOp("linear")
      "exponential" -> emitOp("exponential", args.map(::lowerExpr))
      "cubicBezier" -> emitOp("cubic-bezier", args.map(::lowerExpr))
      "interpolate" -> emitInterpolate("interpolate", args)
      "interpolateHcl" -> emitInterpolate("interpolate-hcl", args)
      "interpolateLab" -> emitInterpolate("interpolate-lab", args)
      "step" -> emitStep(args)
      "match" -> emitMatch(args)
      "nil" -> emitLitNull()
      "rgb" -> emitOp(if (args.size >= 4) "rgba" else "rgb", args.map(::lowerExpr))
      "toRgba" -> emitOp("to-rgba", listOf(lowerReceiver(expression)))
      "format" -> emitFormat(args)
      "span" -> emitSpanValue(args)
      "image" -> emitImage(expression, args)
      "bind" -> emitBind(args)
      "asNumber" -> emitOp("number", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "asString" -> emitOp("string", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "asBoolean" -> emitOp("boolean", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "asColor",
      "convertToColor" ->
        emitOp("to-color", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "asEnum" -> emitAsEnum(expression, args)
      "asMap" -> emitOp("object", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "asList" -> emitAsList(expression, args)
      "asVector" ->
        emitOp(
          "array",
          listOf(lowerReceiver(expression), emitLit(builder.irString("number"))) +
            args.map(::lowerExpr),
        )
      "asOffset",
      "asDpOffset" ->
        emitOp(
          "array",
          listOf(lowerReceiver(expression), emitLit(builder.irString("number")), emitLitOf(2)),
        )
      "asPadding" ->
        emitOp(
          "array",
          listOf(lowerReceiver(expression), emitLit(builder.irString("number")), emitLitOf(4)),
        )
      "convertToNumber" ->
        emitOp("to-number", listOf(lowerReceiver(expression)) + args.map(::lowerExpr))
      "convertToString" -> emitOp("to-string", listOf(lowerReceiver(expression)))
      "convertToBoolean" -> emitOp("to-boolean", listOf(lowerReceiver(expression)))
      "typeOf" -> emitOp("typeof", listOf(lowerReceiver(expression)))
      "getDp",
      "<get-dp>" -> lowerReceiver(expression)
      "getMilliseconds",
      "<get-milliseconds>" -> lowerReceiver(expression)
      "getSeconds",
      "<get-seconds>" -> emitOp("*", listOf(lowerReceiver(expression), emitLitOf(1000)))
      "getSp",
      "<get-sp>" -> emitTextUnit(lowerReceiver(expression), "sp")
      "getEm",
      "<get-em>" -> emitTextUnit(lowerReceiver(expression), "em")
      "collator" -> emitCollator(args)
      "resolvedLocale" -> emitOp("resolved-locale", args.map(::lowerExpr))
      "isScriptSupported" -> emitOp("is-supported-script", listOf(lowerReceiver(expression)))
      "eq" -> emitOp("==", args.map(::lowerExpr))
      "neq" -> emitOp("!=", args.map(::lowerExpr))
      "gt" -> emitOp(">", args.map(::lowerExpr))
      "gte" -> emitOp(">=", args.map(::lowerExpr))
      "lt" -> emitOp("<", args.map(::lowerExpr))
      "lte" -> emitOp("<=", args.map(::lowerExpr))
      "formatToString" -> emitNumberFormat(expression, args)
      "offset" -> emitOffset(expression, args)
      "dpOffset" -> emitComposeHelper("dpOffset", expression, args, "DpOffset")
      "padding" -> emitComposeHelper("padding", expression, args, "DpPadding")
      "projectionTransition" ->
        emitComposeHelper("projectionTransition", expression, args, "ProjectionTransition")
      "textVariableAnchorOffset" -> emitTextVariableAnchorOffset(expression, args)
      "get" -> emitGet(expression, args)
      "has" -> emitHas(expression, args)
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
      else -> {
        report(expression, ExprDiagnostics.unsupportedHelper(name))
        emitLitNull()
      }
    }
  }

  private fun emitGet(expression: IrCall, args: List<IrExpression>): IrExpression {
    val parent = expression.symbol.owner.parentFqName()
    if (parent.contains("FeatureExpr")) {
      return emitOp("get", args.map(::lowerExpr))
    }
    return emitOp("get", args.map(::lowerExpr) + listOf(lowerReceiver(expression)))
  }

  private fun emitHas(expression: IrCall, args: List<IrExpression>): IrExpression {
    val parent = expression.symbol.owner.parentFqName()
    if (parent.contains("FeatureExpr")) {
      return emitOp("has", args.map(::lowerExpr))
    }
    return emitOp("has", args.map(::lowerExpr) + listOf(lowerReceiver(expression)))
  }

  private fun emitAsList(expression: IrCall, args: List<IrExpression>): IrExpression {
    val type = args.getOrNull(0) ?: builder.irNull()
    val length = args.getOrNull(1) ?: builder.irNull()
    return emitOp("array", listOf(lowerReceiver(expression), lowerExpr(type), lowerExpr(length)))
  }

  private fun emitAsEnum(expression: IrCall, args: List<IrExpression>): IrExpression {
    val value = lowerReceiver(expression)
    val entries = args.firstOrNull()?.let(::lowerExpr) ?: emitLitNull()
    val fallbacks = args.drop(1)
    val caseArgs = buildList {
      add(emitOp("in", listOf(value, entries)))
      add(value)
      fallbacks.forEach { fallback ->
        val lowered = lowerExpr(fallback)
        add(emitOp("in", listOf(lowered, entries)))
        add(lowered)
      }
      add(emitLitNull())
    }
    return emitOp("string", listOf(emitOp("case", caseArgs)))
  }

  private fun emitOffset(expression: IrCall, args: List<IrExpression>): IrExpression {
    val firstType = args.firstOrNull()?.type
    val isTextUnit = firstType?.classFqName?.asString() == "androidx.compose.ui.unit.TextUnit"
    if (args.any(::isMapEvaluated)) {
      report(
        expression,
        if (isTextUnit) ExprDiagnostics.textUnitOffsetMustBeConst()
        else ExprDiagnostics.composeOnlyConstructor("Offset"),
      )
      return emitLitNull()
    }
    return if (isTextUnit) {
      emitPassthrough("textUnitOffset", args)
    } else {
      emitPassthrough("numberOffset", args)
    }
  }

  private fun emitComposeHelper(
    emitName: String,
    expression: IrCall,
    args: List<IrExpression>,
    typeName: String,
  ): IrExpression {
    if (args.any(::isMapEvaluated)) {
      report(expression, ExprDiagnostics.composeOnlyConstructor(typeName))
      return emitLitNull()
    }
    return emitPassthrough(emitName, args)
  }

  private fun emitTextVariableAnchorOffset(
    expression: IrCall,
    args: List<IrExpression>,
  ): IrExpression {
    if (args.any(::isMapEvaluated)) {
      report(expression, ExprDiagnostics.composeOnlyConstructor("textVariableAnchorOffset"))
      return emitLitNull()
    }
    val fn = emitFunction("textVariableAnchorOffset")
    return builder.irCall(fn).apply {
      arguments[0] = builder.irGetObject(emitClass)
      arguments[1] = irListOfCopied(args)
    }
  }

  private fun emitImage(expression: IrCall, args: List<IrExpression>): IrExpression {
    val first = args.firstOrNull() ?: return emitLitNull()
    return when {
      ConstableTypes.isImageBitmap(first.type) -> {
        if (args.any(::isMapEvaluated)) {
          report(expression, ExprDiagnostics.imageOptionsMustBeConst())
          emitLitNull()
        } else {
          emitPassthrough("imageBitmap", args)
        }
      }
      ConstableTypes.isPainter(first.type) -> {
        if (args.any(::isMapEvaluated)) {
          report(expression, ExprDiagnostics.imageOptionsMustBeConst())
          emitLitNull()
        } else {
          emitPassthrough("imagePainter", args)
        }
      }
      else -> {
        val fn = emitFunction("imageName")
        builder.irCall(fn).apply {
          arguments[0] = builder.irGetObject(emitClass)
          arguments[1] = lowerExpr(first)
        }
      }
    }
  }

  private fun emitSpanValue(args: List<IrExpression>): IrExpression =
    args.firstOrNull()?.let(::lowerExpr) ?: emitLitNull()

  private fun emitFormat(args: List<IrExpression>): IrExpression {
    val pieces = args.flatMap { arg ->
      val spanCall = arg as? IrCall
      if (spanCall != null && spanCall.symbol.owner.name.asString() == "span") {
        val value =
          namedArg(spanCall, "text")
            ?: namedArg(spanCall, "image")
            ?: valueArgs(spanCall).firstOrNull()
        val font = namedArg(spanCall, "font")
        val color = namedArg(spanCall, "textColor")
        val size = namedArg(spanCall, "textSize")
        listOf(
          value?.let(::lowerExpr) ?: emitLitNull(),
          emitSpanOptions(font, color, size),
        )
      } else {
        listOf(lowerExpr(arg), emitSpanOptions(null, null, null))
      }
    }
    return emitNamedVararg("formatSpans", emptyList(), pieces)
  }

  private fun emitSpanOptions(
    font: IrExpression?,
    color: IrExpression?,
    size: IrExpression?,
  ): IrExpression {
    val fn = emitFunction("spanOptions")
    return builder.irCall(fn).apply {
      arguments[0] = builder.irGetObject(emitClass)
      arguments[1] = font?.let(::lowerExpr) ?: builder.irNull()
      arguments[2] = color?.let(::lowerExpr) ?: builder.irNull()
      arguments[3] = size?.let(::lowerExpr) ?: builder.irNull()
    }
  }

  private fun emitMatch(args: List<IrExpression>): IrExpression {
    require(args.size >= 2) { "match needs an input and a fallback" }
    val input = lowerExpr(args.first())
    val fallback = lowerExpr(args.last())
    val cases = args.drop(1).dropLast(1).flatMap(::flattenPair)
    return emitNamedVararg("match", listOf(input, fallback), cases)
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
        "plus" -> if (ConstableTypes.isStringLike(expression.type)) "concat" else "+"
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
        "contains",
        "containsKey" -> {
          val receiver = operands.first()
          val needle = operands.getOrNull(1) ?: return null
          val opName = if (name == "containsKey") "has" else "in"
          val ordered =
            if (name == "containsKey" || ConstableTypes.isMapLike(receiver.type)) {
              listOf(lowerExpr(needle), lowerExpr(receiver))
            } else {
              listOf(lowerExpr(needle), lowerExpr(receiver))
            }
          return emitOp(opName, ordered)
        }
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
      "toLong" -> return operands.firstOrNull()?.let(::lowerExpr) ?: emitCapture(expression)
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
      "joinToString",
      "join" -> {
        val receiver = operands.first()
        val sep = operands.getOrNull(1)
        return emitOp("join", listOf(lowerExpr(receiver)) + listOfNotNull(sep?.let(::lowerExpr)))
      }
      "get" -> {
        if (operands.size >= 2) {
          val receiver = operands.first()
          val key = operands[1]
          return if (ConstableTypes.isMapLike(receiver.type)) {
            emitOp("get", listOf(lowerExpr(key), lowerExpr(receiver)))
          } else {
            emitOp("at", listOf(lowerExpr(key), lowerExpr(receiver)))
          }
        }
      }
      "containsKey" -> {
        if (operands.size >= 2) {
          return emitOp("has", listOf(lowerExpr(operands[1]), lowerExpr(operands[0])))
        }
      }
    }
    if (name in UNIT_IDENTITY) {
      return if (isMapEvaluated(lowerableReceiver(expression, operands))) {
        lowerReceiverOrFirst(expression, operands)
      } else {
        emitCapture(expression)
      }
    }
    if (name in UNIT_SECONDS) {
      return if (isMapEvaluated(lowerableReceiver(expression, operands))) {
        emitOp("*", listOf(lowerReceiverOrFirst(expression, operands), emitLitOf(1000)))
      } else {
        emitCapture(expression)
      }
    }
    if (name in UNIT_SP) {
      return if (isMapEvaluated(lowerableReceiver(expression, operands))) {
        emitTextUnit(lowerReceiverOrFirst(expression, operands), "sp")
      } else {
        emitCapture(expression)
      }
    }
    if (name in UNIT_EM) {
      return if (isMapEvaluated(lowerableReceiver(expression, operands))) {
        emitTextUnit(lowerReceiverOrFirst(expression, operands), "em")
      } else {
        emitCapture(expression)
      }
    }
    if (name in MATH_OPS) {
      val mapped = if (name == "absoluteValue") "abs" else name
      return emitOp(mapped, operands.map(::lowerExpr))
    }
    return null
  }

  private fun lowerableReceiver(expression: IrCall, operands: List<IrExpression>): IrExpression =
    receiverExpression(expression) ?: operands.firstOrNull() ?: expression

  private fun emitInterpolate(kind: String, args: List<IrExpression>): IrExpression {
    require(args.size >= 2) { "$kind needs a type and an input" }
    val type = lowerExpr(args[0])
    val input = lowerExpr(args[1])
    val stops = args.drop(2).flatMap(::flattenStop)
    return emitNamedVararg("interpolate", listOf(builder.irString(kind), type, input), stops)
  }

  private fun emitStep(args: List<IrExpression>): IrExpression {
    require(args.size >= 2) { "step needs an input and a fallback" }
    val input = lowerExpr(args[0])
    val fallback = lowerExpr(args[1])
    val stops = args.drop(2).flatMap(::flattenStop)
    return emitNamedVararg("step", listOf(input, fallback), stops)
  }

  private fun emitBind(args: List<IrExpression>): IrExpression {
    require(args.size >= 3) { "bind(name, value) { ... }" }
    val name = lowerExpr(args[0])
    val value = lowerExpr(args[1])
    val bodyLambda = args[2] as? IrFunctionExpression ?: error("bind body must be a lambda")
    val param = bodyLambda.function.parameters.last()
    locals[param.symbol] = emitOp("var", listOf(name))
    mapLocals += param.symbol
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

  private fun flattenStop(expression: IrExpression): List<IrExpression> {
    if (expression is IrCall && expression.symbol.owner.name.asString() == "to") {
      val ops = allOperands(expression)
      if (ops.isNotEmpty() && isMapEvaluated(ops[0])) {
        report(ops[0], ExprDiagnostics.stopInputMustBeConst())
      }
      return ops.map(::lowerExpr)
    }
    return flattenPair(expression)
  }

  private fun flattenPair(expression: IrExpression): List<IrExpression> {
    if (expression is IrVararg) {
      return expression.elements.flatMap { element ->
        when (element) {
          is IrExpression -> flattenPair(element)
          is IrSpreadElement -> flattenPair(element.expression)
          else -> {
            report(expression, ExprDiagnostics.unsupportedStatement("vararg"))
            emptyList()
          }
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

  private fun isMapEvaluated(expression: IrExpression): Boolean =
    when (expression) {
      is IrConst,
      is IrGetObjectValue,
      is IrGetEnumValue,
      is IrGetField -> false
      is IrGetValue -> expression.symbol != scopeReceiver?.symbol && expression.symbol in mapLocals
      is IrCall -> isMapEvaluatedCall(expression)
      is IrWhen ->
        expression.branches.any { isMapEvaluated(it.condition) || isMapEvaluated(it.result) }
      is IrStringConcatenation -> expression.arguments.any(::isMapEvaluated)
      is IrConstructorCall -> expression.arguments.filterNotNull().any(::isMapEvaluated)
      is IrTypeOperatorCall -> isMapEvaluated(expression.argument)
      is IrBlock ->
        expression.statements.any { statement ->
          when (statement) {
            is IrVariable -> statement.initializer?.let(::isMapEvaluated) == true
            is IrExpression -> isMapEvaluated(statement)
            else -> false
          }
        }
      is IrReturn -> isMapEvaluated(expression.value)
      is IrVararg ->
        expression.elements.any { element -> element is IrExpression && isMapEvaluated(element) }
      else -> false
    }

  private fun isMapEvaluatedCall(expression: IrCall): Boolean {
    val owner = expression.symbol.owner
    val name = owner.name.asString()
    val parentFq = owner.parentFqName()
    val operands = allOperands(expression)
    if (parentFq in SCOPE_PACKAGES) {
      if (name in COMPOSE_ONLY_HELPERS) return operands.any(::isMapEvaluated)
      if (name == "image") {
        val first = operands.firstOrNull()
        if (
          first != null &&
            (ConstableTypes.isImageBitmap(first.type) || ConstableTypes.isPainter(first.type))
        ) {
          return false
        }
      }
      return true
    }
    if (isRecognizedLowering(name, expression)) {
      return operands.any(::isMapEvaluated)
    }
    return operands.any(::isMapEvaluated)
  }

  private fun isRecognizedLowering(name: String, expression: IrCall): Boolean {
    if (name in MATH_OPS) return true
    if (
      name in
        setOf(
          "plus",
          "minus",
          "times",
          "div",
          "rem",
          "unaryMinus",
          "not",
          "and",
          "or",
          "equals",
          "compareTo",
          "greater",
          "less",
          "greaterOrEqual",
          "lessOrEqual",
          "contains",
          "containsKey",
          "pow",
          "uppercase",
          "lowercase",
          "substring",
          "split",
          "indexOf",
          "joinToString",
          "join",
          "get",
          "length",
        )
    ) {
      return true
    }
    if (name in UNIT_IDENTITY + UNIT_SECONDS + UNIT_SP + UNIT_EM) return true
    return expression.origin == IrStatementOrigin.EQEQ ||
      expression.origin == IrStatementOrigin.EXCLEQ
  }

  private fun emitCapture(expression: IrExpression): IrExpression {
    if (isMapEvaluated(expression)) {
      val callee =
        (expression as? IrCall)?.symbol?.owner?.name?.asString()
          ?: expression::class.simpleName
          ?: "this value"
      val fq = expression.type.classFqName?.asString().orEmpty()
      val message =
        if (
          fq.endsWith("Offset") ||
            fq.endsWith("DpPadding") ||
            fq.endsWith("DpSize") ||
            callee == "listOf" ||
            callee == "mutableListOf"
        ) {
          ExprDiagnostics.composeOnlyConstructor(fq.ifEmpty { callee })
        } else {
          ExprDiagnostics.mapArgsToKotlin(callee)
        }
      report(expression, message)
      return emitLitNull()
    }
    if (!ConstableTypes.isConstable(expression.type)) {
      report(expression, ExprDiagnostics.notConstable(ConstableTypes.typeName(expression.type)))
      return emitLitNull()
    }
    return emitLit(expression)
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

  private fun namedArg(expression: IrCall, name: String): IrExpression? {
    val owner = expression.symbol.owner
    owner.parameters.forEachIndexed { index, parameter ->
      if (parameter.name.asString() == name) return expression.arguments.getOrNull(index)
    }
    return null
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

  private fun receiverExpression(expression: IrCall): IrExpression? {
    val owner = expression.symbol.owner
    owner.parameters.forEachIndexed { index, parameter ->
      if (parameter.kind.name != "ExtensionReceiver") return@forEachIndexed
      val arg = expression.arguments[index]
      if (arg != null && !isScopeReceiver(arg)) return arg
    }
    owner.parameters.forEachIndexed { index, parameter ->
      if (parameter.kind.name != "DispatchReceiver") return@forEachIndexed
      val arg = expression.arguments[index]
      if (arg != null && !isScopeReceiver(arg)) return arg
    }
    return null
  }

  private fun lowerReceiver(expression: IrCall): IrExpression =
    receiverExpression(expression)?.let(::lowerExpr)
      ?: valueArgs(expression).firstOrNull()?.let(::lowerExpr)
      ?: run {
        report(
          expression,
          ExprDiagnostics.unsupportedHelper(expression.symbol.owner.name.asString()),
        )
        emitLitNull()
      }

  private fun lowerReceiverOrFirst(expression: IrCall, operands: List<IrExpression>): IrExpression =
    receiverExpression(expression)?.let(::lowerExpr)
      ?: operands.firstOrNull()?.let(::lowerExpr)
      ?: emitCapture(expression)

  private fun isScopeReceiver(expression: IrExpression?): Boolean {
    val get = expression as? IrGetValue ?: return false
    return get.symbol == scopeReceiver?.symbol
  }

  private fun emitOp(name: String, args: List<IrExpression> = emptyList()): IrExpression {
    val fn = emitFunction("op")
    return builder.irCall(fn).apply {
      arguments[0] = builder.irGetObject(emitClass)
      arguments[1] = builder.irString(name)
      arguments[2] = irListOf(args)
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

  private fun emitLitOf(value: Int): IrExpression {
    val const =
      context.irBuiltIns.intType.let {
        org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl.int(
          builder.startOffset,
          builder.endOffset,
          it,
          value,
        )
      }
    return emitLit(const)
  }

  private fun emitTextUnit(value: IrExpression, type: String): IrExpression {
    val fn = emitFunction("textUnit")
    return builder.irCall(fn).apply {
      arguments[0] = builder.irGetObject(emitClass)
      arguments[1] = value
      arguments[2] = builder.irString(type)
    }
  }

  private fun emitPassthrough(functionName: String, args: List<IrExpression>): IrExpression {
    val fn = emitFunction(functionName)
    return builder.irCall(fn).apply {
      var slot = 0
      arguments[slot++] = builder.irGetObject(emitClass)
      args.forEach { arguments[slot++] = it.deepCopyWithoutPatchingParents() }
    }
  }

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
      arguments[slot] = irListOf(varargArgs)
    }
  }

  private fun irListOf(values: List<IrExpression>): IrExpression {
    val listOf =
      context
        .referenceFunctions(CallableId(FqName("kotlin.collections"), Name.identifier("listOf")))
        .single { symbol ->
          val parameters = symbol.owner.parameters
          parameters.size == 1 && parameters.single().isVararg
        }
    return builder.irCall(listOf).apply {
      typeArguments[0] = context.irBuiltIns.anyNType
      arguments[0] = builder.irVararg(context.irBuiltIns.anyNType, values)
    }
  }

  private fun irListOfCopied(values: List<IrExpression>): IrExpression =
    irListOf(values.map { it.deepCopyWithoutPatchingParents() })

  private fun emitFunction(name: String): IrSimpleFunctionSymbol {
    val id = CallableId(EMIT_CLASS_ID, Name.identifier(name))
    return context.referenceFunctions(id).singleOrNull() ?: error("ExprEmit.$name not found")
  }

  private fun report(element: IrElement, message: String) {
    val offset = element.startOffset
    val location =
      if (offset >= 0) {
        CompilerMessageLocation.create(
          file.fileEntry.name,
          file.fileEntry.getLineNumber(offset) + 1,
          file.fileEntry.getColumnNumber(offset) + 1,
          null,
        )
      } else {
        CompilerMessageLocation.create(file.fileEntry.name, -1, -1, null)
      }
    messageCollector.report(CompilerMessageSeverity.ERROR, message, location)
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
