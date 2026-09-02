package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

internal object GestureTrailScenario : BenchmarkScenario {
  override val id = "gesture-trail"
  override val title = "Gesture trail"
  override val description =
    "Drag the map. A Compose crosshair sits on the pointer; the circle is the world point that started under it."
  override val region = BenchmarkRegion
  override val minZoom = 12
  override val maxZoom = 16
  override val camera = CameraPosition(target = BenchmarkCenter, zoom = 15.0)
  override val usesGestures = true

  @Composable
  override fun MapContent(session: BenchmarkSession) {
    PinLayer(session.pin)
  }

  override suspend fun run(mapState: MapState, session: BenchmarkSession) {
    session.ui.status = "Drag the map"
    session.gestures.arm()
    session.gestures.awaitQualifyingDrag()
  }
}

@Composable
internal fun PinLayer(pin: Position?) {
  if (pin == null) return
  val source =
    rememberGeoJsonSource(
      data =
        GeoJsonData.Features(
          FeatureCollection(listOf(Feature(geometry = Point(pin), properties = null)))
        )
    )
  CircleLayer(
    id = "benchmark-pin",
    source = source,
    radius = const(8.dp),
    color = const(Color(0xFFE53935)),
    strokeWidth = const(2.dp),
    strokeColor = const(Color.White),
  )
}
