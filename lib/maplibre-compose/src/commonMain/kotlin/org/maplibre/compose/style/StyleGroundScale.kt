package org.maplibre.compose.style

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.div
import org.maplibre.compose.expressions.dsl.dp
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.util.metersPerDpAtLatitude

/** Meters per dp at zoom 0 and the camera's latitude, from which zoom curves derive the rest. */
internal const val GroundScaleGlobalState = "${InternalGlobalStatePrefix}meters-per-dp-at-zoom-0"

/** Above MapLibre Native's maximum zoom of 25.5, so the curve never clamps. */
private const val GroundScaleMaxZoom = 26

/** Updates the state once the scale changes by 0.5%, so north-south pans rarely touch the style. */
private val GroundScaleStep = ln(1.005)

private val globalGroundScale =
  globalState(GroundScaleGlobalState).asNumber(const(roundedGroundScale(latitude = 0.0)))

internal fun roundedGroundScale(latitude: Double): Float {
  val scale = metersPerDpAtLatitude(zoom = 0.0, latitude = latitude)
  return exp(round(ln(scale) / GroundScaleStep) * GroundScaleStep).toFloat()
}

// Zoom moves the camera every frame, so the renderer applies it through an exponential zoom curve,
// which is exact for base 2. Only the latitude factor comes from global state.
internal fun metersToDp(meters: Expression<FloatValue>): Expression<DpValue> =
  interpolate(
    exponential(2f),
    zoom(),
    0 to (meters / globalGroundScale).dp,
    GroundScaleMaxZoom to (meters * const(2f.pow(GroundScaleMaxZoom)) / globalGroundScale).dp,
  )
