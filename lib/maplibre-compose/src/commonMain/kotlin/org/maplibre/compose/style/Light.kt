package org.maplibre.compose.style

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.IlluminationAnchor
import org.maplibre.compose.expressions.value.VectorValue

/**
 * The style's global light source, which shades extruded geometry such as a
 * [FillExtrusionLayer][org.maplibre.compose.layers.FillExtrusionLayer].
 *
 * The defaults match the style spec's `light` object. Each expression may use the zoom level.
 *
 * @param anchor Whether extruded geometries are lit relative to the map or viewport.
 * @param position Position of the light source relative to lit geometries, as `[r, a, p]`: `r` is
 *   the distance from the center of the base of an object to its light, `a` is the azimuthal angle
 *   of the light in degrees clockwise from 0° (the top of the viewport when [anchor] is
 *   [IlluminationAnchor.Viewport], or due north when it is [IlluminationAnchor.Map]), and `p` is
 *   the polar angle of the light from 0° (directly above) to 180° (directly below).
 * @param color Color tint for lighting extruded geometries.
 * @param intensity Intensity of lighting. A value in the range of `[0..1]`; higher numbers present
 *   as more extreme contrast.
 * @param positionTransition Timing for changes to [position]. Null uses the style's global
 *   transition.
 * @param colorTransition Timing for changes to [color]. Null uses the style's global transition.
 * @param intensityTransition Timing for changes to [intensity]. Null uses the style's global
 *   transition.
 */
@Immutable
public data class Light(
  val anchor: Expression<IlluminationAnchor> = const(IlluminationAnchor.Viewport),
  val position: Expression<VectorValue<Number>> = const(listOf(1.15f, 210f, 30f)),
  val color: Expression<ColorValue> = const(Color.White),
  val intensity: Expression<FloatValue> = const(0.5f),
  val positionTransition: TransitionOptions? = null,
  val colorTransition: TransitionOptions? = null,
  val intensityTransition: TransitionOptions? = null,
) {
  internal fun toJson(): JsonObject = buildJsonObject {
    putExpression("anchor", anchor)
    putExpression("position", position)
    putExpression("color", color)
    putExpression("intensity", intensity)
    putTransition("position-transition", positionTransition)
    putTransition("color-transition", colorTransition)
    putTransition("intensity-transition", intensityTransition)
  }
}
