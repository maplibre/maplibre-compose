package org.maplibre.compose.expressions.ast

import kotlinx.serialization.json.JsonElement

/**
 * An argument to [call][org.maplibre.compose.expressions.dsl.call]: an [Expression], or JSON from
 * [verbatim][org.maplibre.compose.expressions.dsl.verbatim].
 */
public sealed interface CallArgument

/** A [CallArgument] that is ready to encode as style JSON. */
internal sealed interface CompiledCallArgument : CallArgument

/** A [CallArgument] written to the style JSON as-is. */
internal data class Verbatim(val json: JsonElement) : CompiledCallArgument

internal fun CallArgument.compileArgument(context: ExpressionContext): CompiledCallArgument =
  when (this) {
    is Expression<*> -> compile(context)
    is Verbatim -> this
  }

internal fun CallArgument.visitArgument(block: (Expression<*>) -> Unit) {
  if (this is Expression<*>) visit(block)
}
