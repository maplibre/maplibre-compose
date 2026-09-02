package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

internal class GeoJsonLoadScenario(private val synchronousUpdate: Boolean) : BenchmarkScenario {
  override val id = if (synchronousUpdate) "geojson-load-sync" else "geojson-load-async"
  override val title = if (synchronousUpdate) "GeoJSON load (sync)" else "GeoJSON load (async)"
  override val description =
    if (synchronousUpdate) {
      "Loads thousands of points, then updates them every frame. The next frame includes each update."
    } else {
      "Loads thousands of points, then updates them every frame. An update can miss the next frame."
    }
  override val region = BenchmarkRegion
  override val minZoom = 12
  override val maxZoom = 16
  override val camera = CameraPosition(target = BenchmarkCenter, zoom = 14.0)

  @Composable
  override fun MapContent(session: BenchmarkSession) {
    val data = session.geoJson ?: return
    val source =
      rememberGeoJsonSource(
        data = GeoJsonData.Features(data),
        options = GeoJsonOptions(synchronousUpdate = synchronousUpdate),
      )
    CircleLayer(
      id = "benchmark-geojson",
      source = source,
      radius = const(3.dp),
      color = const(Color(0xFF1565C0)),
      opacity = const(0.75f),
      strokeWidth = const(0.5.dp),
      strokeColor = const(Color.White),
    )
  }

  override suspend fun run(mapState: MapState, session: BenchmarkSession) {
    session.ui.status = "Building $PointCount points"
    session.geoJson = withContext(Dispatchers.Default) { collection(phase = 0.0) }
    repeat(SettleFrames) { withFrameNanos {} }
    session.ui.status = "Updating points"
    repeat(UpdateFrames) { frame ->
      val phase = frame.toDouble() / UpdateFrames
      session.geoJson = withContext(Dispatchers.Default) { collection(phase) }
      withFrameNanos {}
    }
  }

  private fun collection(phase: Double): FeatureCollection<Point, Nothing?> {
    val features =
      List(PointCount) { index ->
        Feature(geometry = Point(positionAt(index, phase)), properties = null)
      }
    return FeatureCollection(features)
  }

  private fun positionAt(index: Int, phase: Double): Position {
    val grid = sqrtCeil(PointCount)
    val column = index % grid
    val row = index / grid
    val u = column.toDouble() / grid
    val v = row.toDouble() / grid
    val jitterLon = cos((index + phase * PointCount) * 0.17) * Jitter
    val jitterLat = sin((index + phase * PointCount) * 0.13) * Jitter
    return Position(
      longitude =
        BenchmarkRegion.west + u * (BenchmarkRegion.east - BenchmarkRegion.west) + jitterLon,
      latitude =
        BenchmarkRegion.south + v * (BenchmarkRegion.north - BenchmarkRegion.south) + jitterLat,
    )
  }

  private fun sqrtCeil(value: Int): Int {
    var root = 1
    while (root * root < value) root++
    return root
  }

  private companion object {
    const val PointCount = 8_000
    const val Jitter = 0.0004
    const val SettleFrames = 30
    const val UpdateFrames = 90
  }
}
