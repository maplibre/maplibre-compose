package org.maplibre.compose.util

import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.internal.JsonStyleValueWriter
import org.maplibre.compose.expressions.internal.writeStyleValue

/** Builds JSON only for callers that need to inspect or assemble a JSON document. */
internal fun CompiledExpression<*>.toStyleJson(): JsonElement =
  JsonStyleValueWriter().also { writeStyleValue(it) }.result
