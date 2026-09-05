package org.maplibre.compose.style

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.util.toStyleJson

/** The style spec's suffix for a property's own transition. */
internal const val TRANSITION_SUFFIX: String = "-transition"

/**
 * Clears a `<property>-transition` key. MapLibre Native rejects a null transition and keeps the
 * previous one; an empty object returns the property to the style's global transition on both
 * engines.
 */
internal val CLEARED_TRANSITION: JsonObject = JsonObject(emptyMap())

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

/**
 * Encodes [this] as the spec's transition object, in milliseconds. The timing is the declared one;
 * the binding scales it by the platform's animator duration scale when it reaches the engine.
 */
internal fun TransitionOptions.toTransitionJson(): JsonObject = buildJsonObject {
  put("duration", duration.toDouble(DurationUnit.MILLISECONDS))
  put("delay", delay.toDouble(DurationUnit.MILLISECONDS))
}

/**
 * Reads a transition object that an engine reported. Returns null unless the object states both a
 * usable duration and a usable delay.
 *
 * An engine times a field that a transition object omits with the style's global transition, and
 * [TransitionOptions] states no such partial timing. The empty object that MapLibre GL JS reports
 * for a cleared transition is one such object.
 */
internal fun JsonElement.toTransitionOptions(): TransitionOptions? {
  val json = this as? JsonObject ?: return null
  val duration = json.transitionMillis("duration") ?: return null
  val delay = json.transitionMillis("delay") ?: return null
  return TransitionOptions(duration, delay)
}

private fun JsonObject.transitionMillis(name: String): Duration? =
  (this[name] as? JsonPrimitive)
    ?.doubleOrNull
    ?.takeIf { it >= 0.0 }
    ?.milliseconds
    ?.takeIf { it.isFinite() }

/**
 * Writes the transition of the property [property] as the spec's `<property>-transition` object, if
 * it is set. [property] is the spec name without the suffix.
 */
internal fun JsonObjectBuilder.putTransition(property: String, options: TransitionOptions?) {
  if (options != null) put(property + TRANSITION_SUFFIX, options.toTransitionJson())
}
