package org.maplibre.compose.style

import kotlin.math.pow
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

/** A smaller change is not written: it moves the edge of a 300 dp feature by under 0.3 dp. */
internal const val GroundScaleTolerance = 0.001f

/** Above MapLibre Native's maximum zoom of 25.5, so the curve never clamps. */
private const val GroundScaleMaxZoom = 26

private val globalGroundScale =
  globalState(GroundScaleGlobalState).asNumber(const(groundScale(latitude = 0.0)))

/**
 * Meters per dp at zoom 0 and [latitude]. MapLibre GL JS sizes its globe and vertical-perspective
 * views to match Mercator at the map center, so this holds at the center in every projection.
 */
internal fun groundScale(latitude: Double): Float =
  metersPerDpAtLatitude(zoom = 0.0, latitude = latitude).toFloat()

// Zoom moves the camera every frame, so the renderer applies it through an exponential zoom curve,
// which is exact for base 2. Only the latitude factor comes from global state.
internal fun metersToDp(meters: Expression<FloatValue>): Expression<DpValue> =
  interpolate(
    exponential(2f),
    zoom(),
    0 to (meters / globalGroundScale).dp,
    GroundScaleMaxZoom to (meters * const(2f.pow(GroundScaleMaxZoom)) / globalGroundScale).dp,
  )
