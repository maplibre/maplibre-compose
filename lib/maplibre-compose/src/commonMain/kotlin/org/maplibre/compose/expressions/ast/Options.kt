package org.maplibre.compose.expressions.ast

/** A [CallArgument] representing a JSON object whose values are expressions. */
internal data class Options(val entries: Map<String, Expression<*>>) : CallArgument

/** [Options] with compiled values. */
internal data class CompiledOptions(val entries: Map<String, CompiledExpression<*>>) :
  CompiledCallArgument
