package org.maplibre.compose.layers

import js.objects.unsafeJso
import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.gljs.createPropertyExpression
import org.maplibre.compose.style.StyleMutationException

/** A data-constant paint property. Zoom changes evaluate both ends of a running transition. */
internal class IndicatorPaint(
  private val name: String,
  initial: JsonElement,
  private val color: Boolean = false,
) {
  private class Transition(
    val target: (Double) -> DoubleArray,
    var prior: Transition? = null,
    val progress: IndicatorAnimation = IndicatorAnimation(1.0),
  ) {
    fun trim(now: Double) {
      if (!progress.active(now)) prior = null else prior?.trim(now)
    }

    fun value(zoom: Double, now: Double): DoubleArray {
      if (!progress.active(now)) prior = null
      val end = target(zoom)
      val from = prior?.value(zoom, now) ?: return end
      val t = progress.value(now)
      return DoubleArray(end.size) { from[it] + (end[it] - from[it]) * t }
    }
  }

  private var transition = Transition(compile(initial))

  fun value(zoom: Double, now: Double): DoubleArray = transition.value(zoom, now)

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

  private fun compile(value: JsonElement): (Double) -> DoubleArray {
    val specification: dynamic = js("({})")
    specification.type = if (color) "color" else "number"
    specification.default = if (color) "white" else 0
    specification["property-type"] = "data-constant"
    specification.expression = js("({interpolated: true, parameters: ['zoom']})")
    val result =
      createPropertyExpression(JSON.parse(value.toString()), "paint.$name", specification)
    if (result.result != "success") {
      throw StyleMutationException(
        "Invalid location indicator paint: ${JSON.stringify(result.value)}",
        null,
      )
    }
    val expression = result.value
    return { zoom ->
      val evaluated = expression.evaluate(unsafeJso<dynamic> { this.zoom = zoom })
      // Style-spec Color components are premultiplied, as required by the custom layer's blend
      // mode.
      if (color)
        doubleArrayOf(
          evaluated.r as Double,
          evaluated.g as Double,
          evaluated.b as Double,
          evaluated.a as Double,
        )
      else doubleArrayOf(evaluated as Double)
    }
  }
}
