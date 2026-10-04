package org.maplibre.compose.expressions.dsl

import kotlin.jvm.JvmInline
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ExpressionValue

/**
 * Binds expression [value] to a [Variable] with the given [name], which can then be referenced
 * inside the block using [use]. For example:
 * ```kt
 * val result = withVariable("x", const(5)) { x ->
 *   x.use() + const(3)
 * }
 * ```
 */
public fun <V : ExpressionValue?, R : ExpressionValue?> withVariable(
  name: String,
  value: Expression<V>,
  block: (Variable<V>) -> Expression<R>,
): Expression<R> = call("let", const(name), value, block(Variable(name)))

/** References a [Variable] bound in [withVariable]. */
public fun <T : ExpressionValue?> Variable<T>.use(): Expression<T> = call("var", const(name))

/** Represents a variable bound with [withVariable]. Reference the bound expression with [use]. */
@JvmInline
public value class Variable<@Suppress("unused") T : ExpressionValue?>
internal constructor(public val name: String)
