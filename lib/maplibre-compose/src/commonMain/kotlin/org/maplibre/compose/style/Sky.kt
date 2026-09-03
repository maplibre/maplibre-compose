package org.maplibre.compose.style

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue

/**
 * The style's sky: the area above the horizon, and the atmosphere around a globe.
 *
 * The defaults match the style spec's `sky` object. Each expression may use the zoom level. The fog
 * properties apply only with 3D terrain, which this library does not yet expose.
 *
 * MapLibre Native does not implement the sky; see
 * [maplibre-native#4414](https://github.com/maplibre/maplibre-native/issues/4414).
 *
 * @param skyColor The base color for the sky.
 * @param horizonColor The base color at the horizon.
 * @param fogColor The base color for the fog.
 * @param fogGroundBlend How to blend the fog over the 3D terrain. A value in the range of `[0..1]`,
 *   where 0 is the map center and 1 is the horizon.
 * @param horizonFogBlend How to blend the fog color and the horizon color. A value in the range of
 *   `[0..1]`, where 0 uses the horizon color only and 1 uses the fog color only.
 * @param skyHorizonBlend How to blend the sky color and the horizon color. A value in the range of
 *   `[0..1]`, where 1 blends the color at the middle of the sky and 0 uses the sky color only.
 * @param atmosphereBlend How visible the atmosphere around a globe is. A value in the range of
 *   `[0..1]`, where 1 shows the atmosphere and 0 hides it. Interpolate it by zoom when using a
 *   globe projection, so that it is hidden once the projection is Mercator.
 * @param skyColorTransition Timing for changes to [skyColor]. Null uses the style's global
 *   transition.
 * @param horizonColorTransition Timing for changes to [horizonColor]. Null uses the style's global
 *   transition.
 * @param fogColorTransition Timing for changes to [fogColor]. Null uses the style's global
 *   transition.
 * @param fogGroundBlendTransition Timing for changes to [fogGroundBlend]. Null uses the style's
 *   global transition.
 * @param horizonFogBlendTransition Timing for changes to [horizonFogBlend]. Null uses the style's
 *   global transition.
 * @param skyHorizonBlendTransition Timing for changes to [skyHorizonBlend]. Null uses the style's
 *   global transition.
 * @param atmosphereBlendTransition Timing for changes to [atmosphereBlend]. Null uses the style's
 *   global transition.
 */
@Immutable
public data class Sky(
  val skyColor: Expression<ColorValue> = const(Color(0xFF88C6FC)),
  val horizonColor: Expression<ColorValue> = const(Color.White),
  val fogColor: Expression<ColorValue> = const(Color.White),
  val fogGroundBlend: Expression<FloatValue> = const(0.5f),
  val horizonFogBlend: Expression<FloatValue> = const(0.8f),
  val skyHorizonBlend: Expression<FloatValue> = const(0.8f),
  val atmosphereBlend: Expression<FloatValue> = const(0.8f),
  val skyColorTransition: TransitionOptions? = null,
  val horizonColorTransition: TransitionOptions? = null,
  val fogColorTransition: TransitionOptions? = null,
  val fogGroundBlendTransition: TransitionOptions? = null,
  val horizonFogBlendTransition: TransitionOptions? = null,
  val skyHorizonBlendTransition: TransitionOptions? = null,
  val atmosphereBlendTransition: TransitionOptions? = null,
) {
  internal fun toJson(): JsonObject = buildJsonObject {
    putExpression("sky-color", skyColor)
    putExpression("horizon-color", horizonColor)
    putExpression("fog-color", fogColor)
    putExpression("fog-ground-blend", fogGroundBlend)
    putExpression("horizon-fog-blend", horizonFogBlend)
    putExpression("sky-horizon-blend", skyHorizonBlend)
    putExpression("atmosphere-blend", atmosphereBlend)
    putTransition("sky-color", skyColorTransition)
    putTransition("horizon-color", horizonColorTransition)
    putTransition("fog-color", fogColorTransition)
    putTransition("fog-ground-blend", fogGroundBlendTransition)
    putTransition("horizon-fog-blend", horizonFogBlendTransition)
    putTransition("sky-horizon-blend", skyHorizonBlendTransition)
    putTransition("atmosphere-blend", atmosphereBlendTransition)
  }
}
