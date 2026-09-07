package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * The live-tracking pattern: one small point source is replaced every frame while a static route
 * line stays on the map. Exercises the per-frame cost of a GeoJSON data update, independent of
 * payload size.
 */
internal object LiveMarkerScenario : BenchmarkScenario {
  override val id = "live-marker"
  override val title = "Live marker"
  override val description =
    "Moves one circle along a route every frame, like a live tracker. The route line never changes."
  override val region = BenchmarkRegion
  override val minZoom = 12
  override val maxZoom = 16
  override val camera = CameraPosition(target = BenchmarkCenter, zoom = 14.0)

  private val route =
    listOf(
      Position(longitude = BenchmarkRegion.west, latitude = BenchmarkRegion.south),
      Position(longitude = -74.012, latitude = 40.706),
      Position(longitude = -74.006, latitude = 40.712),
      Position(longitude = -74.000, latitude = 40.716),
      Position(longitude = BenchmarkRegion.east, latitude = BenchmarkRegion.north),
    )

  private var marker by mutableStateOf(route.first())

  @Composable
  override fun MapContent(session: BenchmarkSession) {
    LaunchedEffect(Unit) {
      val startMillis = withFrameMillis { it }
      while (true) {
        withFrameMillis { frameMillis ->
          val phase = ((frameMillis - startMillis) % LoopMillis).toDouble() / LoopMillis
          marker = positionAt(phase)
        }
      }
    }

    val routeSource =
      rememberGeoJsonSource(
        GeoJsonData.Features(Feature(geometry = LineString(route), properties = null))
      )
    LineLayer(
      id = "benchmark-live-route",
      source = routeSource,
      color = const(Color(0xFF546E7A)),
      width = const(3.dp),
      opacity = const(0.5f),
    )

    val markerSource =
      rememberGeoJsonSource(
        GeoJsonData.Features(Feature(geometry = Point(marker), properties = null))
      )
    CircleLayer(
      id = "benchmark-live-marker",
      source = markerSource,
      radius = const(7.dp),
      color = const(Color(0xFF00695C)),
      strokeWidth = const(2.dp),
      strokeColor = const(Color.White),
    )
  }

  override suspend fun run(mapState: MapState, session: BenchmarkSession) {
    session.ui.status = "Tracking the marker"
    repeat(UpdateFrames) { withFrameNanos {} }
  }

  private fun positionAt(phase: Double): Position {
    val segments = route.size - 1
    val scaled = phase * segments
    val index = scaled.toInt().coerceAtMost(segments - 1)
    val fraction = scaled - index
    val a = route[index]
    val b = route[index + 1]
    return Position(
      longitude = a.longitude + (b.longitude - a.longitude) * fraction,
      latitude = a.latitude + (b.latitude - a.latitude) * fraction,
    )
  }
}

private const val LoopMillis = 20_000L
private const val UpdateFrames = 600
