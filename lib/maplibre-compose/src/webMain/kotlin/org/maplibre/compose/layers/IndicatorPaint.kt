package org.maplibre.compose.layers

import kotlin.js.JsAny
import kotlin.js.JsNumber
import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.gljs.createPropertyExpression
import org.maplibre.compose.gljs.jsGet
import org.maplibre.compose.gljs.jsNumberToDouble
import org.maplibre.compose.gljs.parseJson
import org.maplibre.compose.gljs.stringifyJson
import org.maplibre.compose.style.StyleMutationException

/**
 * A data-constant paint property. Zoom and global-state changes evaluate both ends of a running
 * transition.
 */
internal class IndicatorPaint(
  private val name: String,
  initial: JsonElement,
  private val color: Boolean = false,
) {
  private class Transition(
    val target: (Double, JsAny?) -> DoubleArray,
    var prior: Transition? = null,
    val progress: IndicatorAnimation = IndicatorAnimation(1.0),
  ) {
    fun trim(now: Double) {
      if (!progress.active(now)) prior = null else prior?.trim(now)
    }

    fun value(zoom: Double, globalState: JsAny?, now: Double): DoubleArray {
      if (!progress.active(now)) prior = null
      val end = target(zoom, globalState)
      val from = prior?.value(zoom, globalState, now) ?: return end
      val t = progress.value(now)
      return DoubleArray(end.size) { from[it] + (end[it] - from[it]) * t }
    }
  }

  private var transition = Transition(compile(initial))

  /** Evaluates at [zoom] with the map's [globalState] object, or with no values when null. */
  fun value(zoom: Double, now: Double, globalState: JsAny? = null): DoubleArray =
    transition.value(zoom, globalState, now)

  fun retarget(value: JsonElement, now: Double, delay: Double, duration: Double) {
    val next = compile(value)
    // Native evaluates a still-running prior transition recursively at the current time and
    // zoom. Prune completed history even while hidden, so repeated updates cannot retain it.
    transition.trim(now)
    val progress = IndicatorAnimation(0.0)
    progress.retarget(1.0, now, delay, duration)
    transition = Transition(next, transition, progress)
  }

  fun finish() {
    transition.prior = null
    transition.progress.finish()
  }

  fun active(now: Double): Boolean = transition.prior != null && transition.progress.active(now)

  private fun compile(value: JsonElement): (Double, JsAny?) -> DoubleArray {
    val specification = paintSpecification(color)
    val result =
      createPropertyExpression(parseJson(value.toString())!!, "paint.$name", specification)
    if (expressionResult(result) != "success") {
      throw StyleMutationException(
        "Invalid location indicator paint: ${stringifyJson(jsGet(result, "value"))}",
        null,
      )
    }
    val expression = jsGet(result, "value")!!
    return { zoom, globalState ->
      val evaluated = evaluatePaint(expression, zoom, globalState)
      // Style-spec Color components are premultiplied, as required by the custom layer's blend
      // mode.
      if (color)
        doubleArrayOf(
          paintComponent(evaluated, "r"),
          paintComponent(evaluated, "g"),
          paintComponent(evaluated, "b"),
          paintComponent(evaluated, "a"),
        )
      else doubleArrayOf(jsNumberToDouble(evaluated.unsafeCast<JsNumber>()))
    }
  }
}

private fun paintSpecification(color: Boolean): JsAny =
  js(
    "({type: color ? 'color' : 'number', default: color ? 'white' : 0, 'property-type': 'data-constant', expression: {interpolated: true, parameters: ['zoom']}})",
  )

private fun expressionResult(result: JsAny): String = js("result.result")

private fun evaluatePaint(expression: JsAny, zoom: Double, globalState: JsAny?): JsAny =
  js("expression.evaluate({zoom, globalState})")

private fun paintComponent(value: JsAny, name: String): Double = js("value[name]")
