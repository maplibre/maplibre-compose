package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

internal object ScriptedPanScenario : BenchmarkScenario {
  override val id = "scripted-pan"
  override val title = "Scripted pan trail"
  override val description =
    "Moves the camera along a line while a Compose cursor shows where a pinned point should sit."
  override val region = BenchmarkRegion
  override val minZoom = 12
  override val maxZoom = 16
  override val camera =
    CameraPosition(target = BenchmarkCenter, zoom = 15.0, bearing = 0.0, tilt = 0.0)

  @Composable
  override fun MapContent(session: BenchmarkSession) {
    PinLayer(session.pin)
  }

  override suspend fun run(session: BenchmarkSession) {
    val camera = session.cameraState
    camera.position = camera.position.copy(bearing = 0.0, tilt = 0.0)
    val projection = camera.awaitProjection()
    val startCursor = DpOffset(x = 160.dp, y = 240.dp)
    val pin = projection.positionFromScreenLocation(startCursor)
    session.pin = pin
    session.expectedCursor = startCursor
    session.gestures.arm()
    val originTarget = camera.position.target
    val originMark = TimeSource.Monotonic.markNow()
    val durationNs = Duration.inWholeNanoseconds.toDouble()
    val metersPerDp = camera.metersPerDpAtTarget.coerceAtLeast(0.01)
    val metersPerDegLat = 111_320.0
    val metersPerDegLon = metersPerDegLat * cos(originTarget.latitude * PI / 180.0)
    val dLon = PanEastDp * metersPerDp / metersPerDegLon
    while (true) {
      val t =
        (originMark.elapsedNow().inWholeNanoseconds.toDouble() / durationNs).coerceIn(0.0, 1.0)
      val target =
        Position(
          longitude = originTarget.longitude + dLon * t,
          latitude = originTarget.latitude,
        )
      camera.position = camera.position.copy(target = target)
      val expected = DpOffset(x = startCursor.x - (PanEastDp * t.toFloat()).dp, y = startCursor.y)
      session.expectedCursor = expected
      val expectedPx = with(session.density) { Offset(expected.x.toPx(), expected.y.toPx()) }
      session.gestures.onPointer(expectedPx.x.toDouble(), expectedPx.y.toDouble(), pressed = true)
      val actual = camera.projection?.screenLocationFromPosition(pin)
      if (actual != null) {
        val actualPx = with(session.density) { Offset(actual.x.toPx(), actual.y.toPx()) }
        session.gestures.onMapProjection(actualPx.x.toDouble(), actualPx.y.toDouble())
      }
      if (t >= 1.0) break
      withFrameNanos {}
    }
    session.gestures.onPointer(
      xPx = with(session.density) { (startCursor.x - PanEastDp.dp).toPx().toDouble() },
      yPx = with(session.density) { startCursor.y.toPx().toDouble() },
      pressed = false,
    )
  }

  private const val PanEastDp = 220f
  private val Duration = 2.seconds
}

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

  override suspend fun run(session: BenchmarkSession) {
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
