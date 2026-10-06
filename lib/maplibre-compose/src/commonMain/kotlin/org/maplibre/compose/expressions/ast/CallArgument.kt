package org.maplibre.compose.expressions.ast

import kotlinx.serialization.json.JsonElement

/**
 * An argument to [call][org.maplibre.compose.expressions.dsl.call]: an [Expression], or a value
 * from [verbatim][org.maplibre.compose.expressions.dsl.verbatim] or
 * [options][org.maplibre.compose.expressions.dsl.options].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface CallArgument

/** A [CallArgument] that is ready to encode as style JSON. */
internal sealed interface CompiledCallArgument : CallArgument

/** A [CallArgument] written to the style JSON as-is. */
internal data class Verbatim(val json: JsonElement) : CompiledCallArgument

internal fun CallArgument.compileArgument(context: ExpressionContext): CompiledCallArgument =
  when (this) {
    is Expression<*> -> compile(context)
    is Options -> CompiledOptions(entries.mapValues { it.value.compile(context) })
    is CompiledCallArgument -> this
  }

internal fun CallArgument.visitArgument(block: (Expression<*>) -> Unit) {
  when (this) {
    is Expression<*> -> visit(block)
    is Options -> entries.values.forEach { it.visit(block) }
    is CompiledOptions -> entries.values.forEach { it.visit(block) }
    is Verbatim -> {}
  }
}
