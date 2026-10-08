package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.center
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees

object LiveTrackingDemo : Demo {
  override val name = "Live tracking"
  override val description = "Follow a ferry across Elliott Bay as its remaining route disappears."
  private val routeRegion =
    BoundingBox(west = -122.5195, south = 47.5925, east = -122.3298, north = 47.6321)
  override val destination = DemoDestination.FitBounds(routeRegion)
  override val pointerPin = DemoPointerPin(routeRegion.center, destination)

  // The Seattle-Bainbridge ferry crossing, traced from OpenStreetMap (ODbL).
  private val route =
    listOf(
      Position(longitude = -122.33984, latitude = 47.6029),
      Position(longitude = -122.34082, latitude = 47.60286),
      Position(longitude = -122.3412, latitude = 47.60285),
      Position(longitude = -122.34171, latitude = 47.60287),
      Position(longitude = -122.35677, latitude = 47.60275),
      Position(longitude = -122.36675, latitude = 47.60249),
      Position(longitude = -122.39216, latitude = 47.60333),
      Position(longitude = -122.43808, latitude = 47.60495),
      Position(longitude = -122.47185, latitude = 47.60687),
      Position(longitude = -122.48353, latitude = 47.60733),
      Position(longitude = -122.4864, latitude = 47.60768),
      Position(longitude = -122.4891, latitude = 47.60839),
      Position(longitude = -122.49082, latitude = 47.60945),
      Position(longitude = -122.492, latitude = 47.61066),
      Position(longitude = -122.4934, latitude = 47.61316),
      Position(longitude = -122.49496, latitude = 47.61609),
      Position(longitude = -122.49591, latitude = 47.61765),
      Position(longitude = -122.49737, latitude = 47.61944),
      Position(longitude = -122.49904, latitude = 47.62034),
      Position(longitude = -122.50123, latitude = 47.62089),
      Position(longitude = -122.507, latitude = 47.62174),
      Position(longitude = -122.50833, latitude = 47.62199),
      Position(longitude = -122.50951, latitude = 47.62214),
    )

  // The real ferry's ~8 m/s is imperceptible with the whole crossing in the viewport.
  private const val SpeedMetersPerSecond = 250.0

  // Off by default so the initial flight runs uninterrupted.
  private var followVehicle by mutableStateOf(false)
  private var showRemainingCrossing by mutableStateOf(true)
  private var crossing by mutableStateOf(Crossing(distance = 0.0, outbound = true))

  private data class Crossing(val distance: Double, val outbound: Boolean)

  override fun interactions(mapState: MapState, settings: MapInteractions): MapInteractions =
    MapInteractions(settings) { camera { pan { onStart { followVehicle = false } } } }

  private val segmentLengths = route.zipWithNext { a, b -> approximateDistanceMeters(a, b) }

  private val routeLength = segmentLengths.sum()

  /**
   * Distance between nearby positions on an equirectangular projection, accurate to well under a
   * percent at this scale.
   */
  private fun approximateDistanceMeters(a: Position, b: Position): Double {
    val metersPerDegree = 111_320.0
    val dLat = (b.latitude - a.latitude) * metersPerDegree
    val dLon =
      (b.longitude - a.longitude) *
        metersPerDegree *
        cos((a.latitude + b.latitude) / 2 * (PI / 180))
    return sqrt(dLat * dLat + dLon * dLon)
  }

  /** The route segment holding [distance] from the start, and the fraction along it. */
  private fun segmentAt(distance: Double): Pair<Int, Double> {
    var remaining = distance
    for ((index, length) in segmentLengths.withIndex()) {
      if (remaining <= length) {
        return index to if (length == 0.0) 0.0 else remaining / length
      }
      remaining -= length
    }
    return segmentLengths.lastIndex to 1.0
  }

  private fun positionAt(distance: Double): Position {
    val (index, fraction) = segmentAt(distance)
    val a = route[index]
    val b = route[index + 1]
    return Position(
      longitude = a.longitude + (b.longitude - a.longitude) * fraction,
      latitude = a.latitude + (b.latitude - a.latitude) * fraction,
    )
  }

  /** The heading along the segment holding [distance], reversed on the return crossing. */
  private fun bearingAt(distance: Double, outbound: Boolean): Bearing {
    val (index, _) = segmentAt(distance)
    val (a, b) =
      if (outbound) route[index] to route[index + 1] else route[index + 1] to route[index]
    val dLat = b.latitude - a.latitude
    val dLon = (b.longitude - a.longitude) * cos((a.latitude + b.latitude) / 2 * (PI / 180))
    return Bearing.North + (atan2(dLon, dLat) * 180 / PI).degrees
  }

  @Composable
  override fun MapContent(style: DemoStyle) {
    val mapState = checkNotNull(LocalMapState.current)
    LaunchedEffect(Unit) {
      val startMillis = withFrameMillis { it }
      while (true) {
        withFrameMillis { frameMillis ->
          val traveled = (frameMillis - startMillis) / 1000.0 * SpeedMetersPerSecond
          // Reverse direction at each terminal.
          val phase = traveled % (2 * routeLength)
          val outbound = phase < routeLength
          val distance = routeLength - abs(phase - routeLength)
          crossing = Crossing(distance, outbound)
        }
        if (followVehicle && !mapState.isCameraMoving) {
          mapState.setCameraPosition(
            mapState.cameraPosition.copy(center = positionAt(crossing.distance))
          )
        }
      }
    }

    val routeSource =
      rememberGeoJsonSource(
        GeoJsonData.Features(Feature(geometry = LineString(route), properties = null))
      )
    LineLayer(
      id = "ferry-route",
      source = routeSource,
      color = const(Color(0xFF546E7A)),
      width = const(3.dp),
      opacity = const(0.5f),
      dasharray = const(listOf(1, 2)),
    )

    val vehiclePosition = positionAt(crossing.distance)
    if (showRemainingCrossing) {
      val (index, _) = segmentAt(crossing.distance)
      // Start at the animated position and keep only the vertices ahead of the ferry.
      val remainingVertices =
        if (crossing.outbound) route.drop(index + 1) else route.take(index + 1).asReversed()
      val remainingSource =
        rememberGeoJsonSource(
          GeoJsonData.Features(
            Feature(
              geometry = LineString(listOf(vehiclePosition) + remainingVertices),
              properties = null,
            )
          )
        )
      LineLayer(
        id = "remaining-crossing",
        source = remainingSource,
        color = const(Color(0xFF0077B6)),
        width = const(5.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
      )
    }

    // Position and route share the frame clock, so the indicator must not add another animation.
    LocationIndicatorLayer(
      id = "ferry-vehicle",
      location = vehiclePosition,
      bearing = bearingAt(crossing.distance, crossing.outbound),
      locationTransition = TransitionOptions(duration = Duration.ZERO),
    )
  }

  @Composable
  override fun PeekPanel(state: DemoAppState) {
    SwitchRow(
      label = "Follow the ferry",
      checked = followVehicle,
      onCheckedChange = { followVehicle = it },
    )
  }

  @Composable
  override fun Panel(state: DemoAppState) {
    val remainingMeters =
      if (crossing.outbound) routeLength - crossing.distance else crossing.distance
    val distanceLabel =
      if (remainingMeters < 1000) "${remainingMeters.roundToInt()} m"
      else "${(remainingMeters / 100).roundToInt() / 10.0} km"
    ListItem(
      headlineContent = { Text(if (crossing.outbound) "To Bainbridge Island" else "To Seattle") },
      supportingContent = { Text("$distanceLabel remaining") },
    )
    SwitchRow(
      label = "Show remaining crossing",
      checked = showRemainingCrossing,
      onCheckedChange = { showRemainingCrossing = it },
    )
    Text(
      "Panning stops follow. Zooming and rotating keep it enabled.",
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
  }
}
