package org.maplibre.compose.expressions.dsl

import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.expressions.ast.CallArgument
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.ast.Verbatim
import org.maplibre.compose.expressions.value.ExpressionValue

/**
 * Calls the MapLibre expression [operator] with [args]. Use it for an operator that this DSL does
 * not provide.
 *
 * The result has the type [T] that the caller chooses; the library does not check it against the
 * operator. An [Expression] argument is encoded as an expression, so `const(listOf(1, 2))` is
 * encoded as `["literal", [1, 2]]`. Where the operator reads an argument as plain JSON, such as an
 * options object, pass it with [verbatim].
 *
 * The renderer must support the operator.
 */
public fun <T : ExpressionValue?> call(operator: String, vararg args: CallArgument): Expression<T> =
  FunctionCall.of(operator, *args).cast()

/**
 * Creates a [call] argument that is written to the style JSON as [json], unchanged. Use it where
 * the operator reads an argument as plain JSON instead of evaluating it as an expression.
 */
public fun verbatim(json: JsonElement): CallArgument = Verbatim(json)
