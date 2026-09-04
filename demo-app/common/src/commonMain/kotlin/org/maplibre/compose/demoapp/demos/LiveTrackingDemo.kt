package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.center
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapStyleScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

object LiveTrackingDemo : Demo {
  override val name = "Live tracking"
  override val description = "A simulated ferry crosses Elliott Bay with a camera-follow toggle."
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
  private const val SPEED_METERS_PER_SECOND = 250.0

  // Off by default so the initial flight runs uninterrupted.
  private var followVehicle by mutableStateOf(false)
  private var vehiclePosition by mutableStateOf(route.first())

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

  private fun positionAt(distance: Double): Position {
    var remaining = distance
    for ((index, length) in segmentLengths.withIndex()) {
      if (remaining <= length) {
        val fraction = if (length == 0.0) 0.0 else remaining / length
        val a = route[index]
        val b = route[index + 1]
        return Position(
          longitude = a.longitude + (b.longitude - a.longitude) * fraction,
          latitude = a.latitude + (b.latitude - a.latitude) * fraction,
        )
      }
      remaining -= length
    }
    return route.last()
  }

  @Composable
  override fun MapStyleScope.MapContent() {
    LaunchedEffect(mapState.cameraMoveReason) {
      if (mapState.cameraMoveReason == CameraMoveReason.GESTURE) {
        followVehicle = false
      }
    }

    LaunchedEffect(Unit) {
      val startMillis = withFrameMillis { it }
      while (true) {
        withFrameMillis { frameMillis ->
          val traveled = (frameMillis - startMillis) / 1000.0 * SPEED_METERS_PER_SECOND
          // Ping-pong between the terminals, like the ferry itself.
          val phase = traveled % (2 * routeLength)
          vehiclePosition = positionAt(routeLength - abs(phase - routeLength))
        }
        if (followVehicle) {
          mapState.setCameraPosition(mapState.cameraPosition.copy(target = vehiclePosition))
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

    val vehicleSource =
      rememberGeoJsonSource(
        GeoJsonData.Features(Feature(geometry = Point(vehiclePosition), properties = null))
      )
    CircleLayer(
      id = "ferry-vehicle",
      source = vehicleSource,
      radius = const(7.dp),
      color = const(Color(0xFF00695C)),
      strokeWidth = const(2.dp),
      strokeColor = const(Color.White),
    )
  }

  @Composable
  override fun Panel(state: DemoAppState) {
    SwitchRow(
      label = "Follow the ferry",
      checked = followVehicle,
      onCheckedChange = { followVehicle = it },
    )
  }
}
