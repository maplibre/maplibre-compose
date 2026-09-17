package org.maplibre.compose.expressions.dsl

import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.value.AnyValue
import org.maplibre.compose.expressions.value.FloatValue

/**
 * Gets the current zoom level. Note that in layer style properties, [zoom] may only appear as the
 * input to a top-level [step] or [interpolate] (, [interpolateHcl], [interpolateLab], ...)
 * expression.
 */
public fun zoom(): Expression<FloatValue> = FunctionCall.of("zoom").cast()

/**
 * Gets the kernel density estimation of a pixel in a heatmap layer, which is a relative measure of
 * how many data points are crowded around a particular pixel. Can only be used in the expression
 * for the `color` parameter in a HeatmapLayer
 * [HeatmapLayer][org.maplibre.compose.layers.HeatmapLayer].
 */
public fun heatmapDensity(): Expression<FloatValue> = FunctionCall.of("heatmap-density").cast()

/**
 * Gets the elevation of a pixel in meters. Can only be used in the expression for the `color`
 * parameter in a [ColorReliefLayer][org.maplibre.compose.layers.ColorReliefLayer].
 */
public fun elevation(): Expression<FloatValue> = FunctionCall.of("elevation").cast()

/**
 * Reads [key] from the loaded style's global state, or null when it has no value.
 *
 * The key is a literal string, not an expression. Use assertions such as [asBoolean], [asNumber],
 * or [asString], or a conversion such as [convertToColor], to give the runtime value a type and
 * fallback. Set values through [org.maplibre.compose.map.MapStyleState.globalState]. The base
 * style's root `state` object supplies defaults; replacing the base style resets all runtime
 * values.
 */
public fun globalState(key: String): Expression<AnyValue?> =
  FunctionCall.of("global-state", const(key)).cast()
