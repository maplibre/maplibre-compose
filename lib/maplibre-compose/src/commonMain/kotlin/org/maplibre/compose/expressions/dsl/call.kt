package org.maplibre.compose.expressions.dsl

import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.expressions.ast.CallArgument
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.ast.Options
import org.maplibre.compose.expressions.ast.Verbatim
import org.maplibre.compose.expressions.value.ExpressionValue

/**
 * Calls the MapLibre expression [operator] with [args]. Use it for an operator that this DSL does
 * not provide.
 *
 * The result has the type [T] that the caller chooses; the library does not check it against the
 * operator. An [Expression] argument is encoded as an expression, so `const(listOf(1, 2))` is
 * encoded as `["literal", [1, 2]]`. Where the operator reads an argument as plain JSON, pass it
 * with [verbatim]; where it reads an object of expressions, pass it with [options].
 *
 * The renderer must support the operator.
 *
 * Copies [args] so later changes to the list do not change the expression.
 */
public fun <T : ExpressionValue?> call(operator: String, args: List<CallArgument>): Expression<T> =
  FunctionCall.of(operator, args).cast()

/** Calls the MapLibre expression [operator] with [args]. See the list overload of [call]. */
public fun <T : ExpressionValue?> call(operator: String, vararg args: CallArgument): Expression<T> =
  call(operator, args.asList())

/**
 * Creates a [call] argument that is written to the style JSON as [json], unchanged. Use it where
 * the operator reads an argument as plain JSON instead of evaluating it as an expression.
 */
public fun verbatim(json: JsonElement): CallArgument = Verbatim(json)

/**
 * Creates a [call] argument that is written as a JSON object of the [entries] whose value is not
 * `null`. Use it where the operator reads an object whose values are expressions, such as the
 * options of `collator` or `number-format`.
 */
public fun options(vararg entries: Pair<String, Expression<*>?>): CallArgument =
  Options(buildMap { for ((key, value) in entries) if (value != null) put(key, value) })
