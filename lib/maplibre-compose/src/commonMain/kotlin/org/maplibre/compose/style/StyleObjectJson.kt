package org.maplibre.compose.style

import kotlin.time.DurationUnit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.util.toStyleJson

/**
 * Writes [expression] under [name], omitting a null literal because the style spec permits no null
 * property values.
 *
 * The root style objects take no context: none of their properties measures text or refers to an
 * image.
 */
internal fun JsonObjectBuilder.putExpression(name: String, expression: Expression<*>) {
  val json = expression.compile(ExpressionContext.None).toStyleJson()
  if (json !is JsonNull) put(name, json)
}

/** Writes [options] as the spec's `<property>-transition` object under [name], if it is set. */
internal fun JsonObjectBuilder.putTransition(name: String, options: TransitionOptions?) {
  if (options == null) return
  putJsonObject(name) {
    put("duration", options.duration.toDouble(DurationUnit.MILLISECONDS))
    put("delay", options.delay.toDouble(DurationUnit.MILLISECONDS))
  }
}
