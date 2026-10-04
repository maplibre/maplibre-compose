package org.maplibre.compose.expressions.dsl

import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.value.ExpressionValue

/**
 * Calls the MapLibre expression [operator] with [args]. Use it for an operator that this DSL does
 * not provide.
 *
 * The result has the type [T] that the caller chooses; the library does not check it against the
 * operator. Each argument is encoded as an expression, so `const(listOf(1, 2))` is encoded as
 * `["literal", [1, 2]]`.
 *
 * The renderer must support the operator.
 */
public fun <T : ExpressionValue?> call(
  operator: String,
  vararg args: Expression<*>,
): Expression<T> = FunctionCall.of(operator, *args).cast()
