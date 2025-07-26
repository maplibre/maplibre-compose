package org.maplibre.compose.expressions.dsl

import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.FunctionCall
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
