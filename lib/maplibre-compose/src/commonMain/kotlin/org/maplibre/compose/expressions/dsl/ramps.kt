package org.maplibre.compose.expressions.dsl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.InterpolatableValue

/**
 * Produces discrete, stepped results by evaluating a piecewise-constant function defined by pairs
 * of input and output values ([stops]). Returns the output value of the stop just less than the
 * [input], or the [fallback] if the input is less than the first stop.
 *
 * Example:
 * ```kt
 * step(zoom(), const(0), 10 to const(2.5), 20 to const(10.5))
 * ```
 *
 * returns 0 if the zoom is less than 10, 2.5 if the zoom is between 10 and less than 20, 10.5 if
 * the zoom is greater than or equal 20.
 */
public fun <T : ExpressionValue?> step(
  input: Expression<FloatValue>,
  fallback: Expression<T>,
  vararg stops: Pair<Number, Expression<T>>,
): Expression<T> = call("step", listOf(input, fallback) + stopArguments(stops))

/** Returns the [stops] as alternating input and output arguments, in increasing input order. */
private fun stopArguments(stops: Array<out Pair<Number, Expression<*>>>): List<Expression<*>> =
  stops
    .map { (input, output) -> input.toFloat() to output }
    .sortedBy { it.first }
    .flatMap { (input, output) -> listOf(const(input), output) }

/**
 * Produces continuous, smooth results by interpolating between pairs of input and output values
 * ([stops]), given the [input] value.
 *
 * Requires the [type] of interpolation to use. Use [linear], [exponential], or [cubicBezier].
 *
 * Example:
 * ```kt
 * interpolate(
 *   exponential(2f), zoom(),
 *   16 to const(1),
 *   24 to const(256),
 * )
 * ```
 *
 * interpolates exponentially from 1 to 256 in zoom levels 16 to 24. Below zoom 16, it is 1, above
 * zoom 24, it is 256. Applied to for example line width, this has the visual effect that the line
 * stays the same width in meters on the map (rather than on the viewport).
 */
public fun <T, V : InterpolatableValue<T>> interpolate(
  type: Interpolation,
  input: Expression<FloatValue>,
  vararg stops: Pair<Number, Expression<V>>,
): Expression<V> = call("interpolate", listOf(verbatim(type.json), input) + stopArguments(stops))

/**
 * Produces continuous, smooth results by interpolating between pairs of input and output values
 * ([stops]), given the [input] value. Works like [interpolate], but the interpolation is performed
 * in the [Hue-Chroma-Luminance color space](https://en.wikipedia.org/wiki/HCL_color_space).
 *
 * Requires the [type] of interpolation to use. Use [linear], [exponential], or [cubicBezier].
 *
 * Example:
 * ```kt
 * interpolateHcl(
 *   linear(),
 *   zoom(),
 *   1 to const(Color.Red),
 *   5 to const(Color.Blue),
 *   10 to const(Color.Green)
 * )
 * ```
 *
 * interpolates linearly from red to blue between in zoom levels 1 to 5, then interpolates linearly
 * from blue to green in zoom levels 5 to 10, which it where it remains until maximum zoom.
 */
public fun interpolateHcl(
  type: Interpolation,
  input: Expression<FloatValue>,
  vararg stops: Pair<Number, Expression<ColorValue>>,
): Expression<ColorValue> =
  call("interpolate-hcl", listOf(verbatim(type.json), input) + stopArguments(stops))

/**
 * Produces continuous, smooth results by interpolating between pairs of input and output values
 * ([stops]), given the [input] value. Works like [interpolate], but the interpolation is performed
 * in the [CIELAB color space](https://en.wikipedia.org/wiki/CIELAB_color_space).
 *
 * Requires the [type] of interpolation to use. Use [linear], [exponential], or [cubicBezier].
 */
public fun interpolateLab(
  type: Interpolation,
  input: Expression<FloatValue>,
  vararg stops: Pair<Number, Expression<ColorValue>>,
): Expression<ColorValue> =
  call("interpolate-lab", listOf(verbatim(type.json), input) + stopArguments(stops))

/**
 * How [interpolate], [interpolateHcl], and [interpolateLab] compute values between stops. Create
 * one with [linear], [exponential], or [cubicBezier].
 *
 * MapLibre reads the interpolation when the style loads, so it is a fixed value, not an expression.
 */
public data class Interpolation internal constructor(internal val json: JsonArray)

/** Interpolates linearly between the pairs of stops. */
public fun linear(): Interpolation = Interpolation(JsonArray(listOf(JsonPrimitive("linear"))))

/**
 * Interpolates exponentially between the stops.
 *
 * @param [base] controls the rate at which the output increases: higher values make the output
 *   increase more towards the high end of the range. With values close to 1 the output increases
 *   linearly.
 */
public fun exponential(base: Float): Interpolation =
  Interpolation(JsonArray(listOf(JsonPrimitive("exponential"), JsonPrimitive(base))))

/**
 * Interpolates using the cubic bezier curve defined by the control points ([x1], [y1]) and ([x2],
 * [y2]) between the pairs of stops. Each coordinate must be between 0 and 1.
 */
public fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float): Interpolation {
  val points = listOf(x1, y1, x2, y2)
  require(points.all { it in 0f..1f }) { "Cubic bezier control points must be between 0 and 1" }
  return Interpolation(
    JsonArray(listOf(JsonPrimitive("cubic-bezier")) + points.map(::JsonPrimitive))
  )
}
