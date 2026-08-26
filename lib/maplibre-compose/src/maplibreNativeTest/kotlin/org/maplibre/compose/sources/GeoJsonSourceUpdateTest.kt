package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.TestThread
import org.maplibre.compose.mlnffi.fileUrlOf
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * Renders one point at the screen center, then replaces the source's data with a point far
 * offscreen. The center pixel must return to the background, which only happens when the update
 * reaches native, native re-tiles, and a frame presents the result.
 */
class GeoJsonSourceUpdateTest {

  @Test
  fun setData_moves_a_rendered_point() =
    assertUpdateMovesPoint(synchronousUpdate = false) { source, data ->
      source.setData(GeoJsonData.Features(data))
    }

  @Test
  fun setData_moves_a_rendered_point_with_synchronous_tiling() =
    assertUpdateMovesPoint(synchronousUpdate = true) { source, data ->
      source.setData(GeoJsonData.Features(data))
    }

  @Test
  fun publishData_moves_a_rendered_point() =
    assertUpdateMovesPoint(synchronousUpdate = false) { source, data ->
      source.publishData(GeoJsonData.Features(data))
    }

  @Test
  fun publishData_moves_a_rendered_point_with_synchronous_tiling() =
    assertUpdateMovesPoint(synchronousUpdate = true) { source, data ->
      source.publishData(GeoJsonData.Features(data))
    }

  @Test
  fun setData_moves_a_rendered_point_when_frames_are_on_demand() =
    assertUpdateMovesPoint(synchronousUpdate = false, onDemand = true) { source, data ->
      source.setData(GeoJsonData.Features(data))
    }

  @Test
  fun publishData_moves_a_rendered_point_when_frames_are_on_demand() =
    assertUpdateMovesPoint(synchronousUpdate = false, onDemand = true) { source, data ->
      source.publishData(GeoJsonData.Features(data))
    }

  /**
   * The GeoJSON benchmark publishes every frame, each from a fresh coroutine (a LaunchedEffect
   * relaunch on the main dispatcher, so the generation ticks at frame rate while parses run on a
   * worker). A stream that outpaces the parse must still install, conflated to the newest data.
   */
  @OptIn(ExperimentalAtomicApi::class)
  @Test
  fun rapid_publishes_install_during_the_stream() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertIs<MlnFfiStyleBinding>(fixture.style, "Errors: ${fixture.errors}")
      val source = GeoJsonSource(SOURCE_ID, GeoJsonData.Features(manyPoints(0.0)), GeoJsonOptions())
      style.addSource(source)

      val sawInstall = AtomicBoolean(false)
      val poller =
        launch(Dispatchers.Default) {
          while (isActive) {
            if (installedBatch(source) != 0.0) {
              sawInstall.store(true)
              break
            }
            delay(POLL_INTERVAL_MILLIS)
          }
        }
      // Launched on the test's event loop, standing in for the main dispatcher: publications
      // tick at frame rate regardless of how backed up the parse workers are.
      val publisher = launch {
        var batch = 1
        while (isActive) {
          val batchNumber = batch++
          launch { source.publishData(GeoJsonData.Features(manyPoints(batchNumber.toDouble()))) }
          delay(FRAME_INTERVAL_MILLIS)
        }
      }
      try {
        val deadline = TimeSource.Monotonic.markNow() + STREAM_TIMEOUT
        while (!sawInstall.load()) {
          check(deadline.hasNotPassedNow()) {
            "No publication installed while updates streamed every ${FRAME_INTERVAL_MILLIS}ms"
          }
          delay(POLL_INTERVAL_MILLIS)
        }
      } finally {
        publisher.cancelAndJoin()
        poller.cancelAndJoin()
      }
    }
  }

  /**
   * A newer one-point payload remains after a slower older parse finishes. `publishData` parses on
   * Default without a suspend point, and the older worker runs to completion.
   */
  @Test
  fun a_newer_publish_keeps_its_data_when_an_older_parse_finishes_later() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.pumpUntilRendered()
      val style = assertIs<MlnFfiStyleBinding>(fixture.style, "Errors: ${fixture.errors}")
      val source = GeoJsonSource(SOURCE_ID, GeoJsonData.Features(pointAt(ORIGIN)), GeoJsonOptions())
      style.addSource(source)

      val older = GeoJsonData.Features(manyPoints(longitude = 2.0))
      val newer = GeoJsonData.Features(pointAt(Position(longitude = 9.0, latitude = 0.0)))
      val olderJob =
        launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
          source.publishData(older)
        }
      source.publishData(newer)
      olderJob.join()

      val json = source.toJson()
      val features = (json["data"] as JsonObject)["features"] as JsonArray
      assertEquals(1, features.size, json.toString())
      assertEquals(9.0, installedBatch(source), json.toString())
      assertEquals(emptyList(), fixture.errors, "the map should report nothing")
    }
  }

  /**
   * A URI has no parse, so it installs without waiting for an in-flight inline parse. When the
   * older parse finishes, the URI remains.
   *
   * The inline job starts on a worker that is not Default, so `withContext(Default)` inside the
   * parse suspends instead of finishing before `launch` returns.
   */
  @Test
  fun a_uri_publish_installs_while_an_inline_parse_is_still_running() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.pumpUntilRendered()
      val style = assertIs<MlnFfiStyleBinding>(fixture.style, "Errors: ${fixture.errors}")
      val source = GeoJsonSource(SOURCE_ID, GeoJsonData.Features(pointAt(ORIGIN)), GeoJsonOptions())
      style.addSource(source)

      val cache = FfiTestPlatform.createCacheFile()
      TestThread("geojson-parse").use { worker ->
        try {
          val file = Path(requireNotNull(cache.parent), "points.geojson")
          SystemFileSystem.sink(file).buffered().use {
            it.writeString("""{"type":"FeatureCollection","features":[]}""")
          }
          val url = fileUrlOf(file)
          val olderJob =
            launch(worker.dispatcher, start = CoroutineStart.UNDISPATCHED) {
              source.publishData(GeoJsonData.Features(manyPoints(longitude = 2.0)))
            }
          val featuresBeforeUri = (source.toJson()["data"] as JsonObject)["features"] as JsonArray
          assertEquals(1, featuresBeforeUri.size, source.toJson().toString())
          source.publishData(GeoJsonData.Uri(url))
          assertEquals(JsonPrimitive(url), source.toJson()["data"], source.toJson().toString())
          olderJob.join()
          assertEquals(JsonPrimitive(url), source.toJson()["data"], source.toJson().toString())
          assertEquals(emptyList(), fixture.errors, "the map should report nothing")
        } finally {
          FfiTestPlatform.deleteCacheFile(cache)
        }
      }
    }
  }

  /** The batch number the installed data was published with; every feature shares it. */
  private fun installedBatch(source: GeoJsonSource): Double {
    val features = (source.toJson()["data"] as JsonObject)["features"] as JsonArray
    val first = features.firstOrNull() as? JsonObject ?: return Double.NaN
    val coordinates = (first["geometry"] as JsonObject)["coordinates"] as JsonArray
    return coordinates[0].jsonPrimitive.double
  }

  private fun manyPoints(longitude: Double): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      repeat(STREAM_POINT_COUNT) { index ->
        addFeature(geometry = Point(Position(longitude, latitude = index * 0.0001)))
      }
    }

  private fun assertUpdateMovesPoint(
    synchronousUpdate: Boolean,
    onDemand: Boolean = false,
    update: suspend (GeoJsonSource, FeatureCollection<Geometry, JsonObject?>) -> Unit,
  ) = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(STYLE)
      val style = assertIs<MlnFfiStyleBinding>(fixture.style, "Errors: ${fixture.errors}")
      fixture.core.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 14.0))

      val source =
        GeoJsonSource(
          SOURCE_ID,
          GeoJsonData.Features(pointAt(ORIGIN)),
          GeoJsonOptions(synchronousUpdate = synchronousUpdate),
        )
      style.addSource(source)
      val layer = CircleLayer(LAYER_ID, source)
      layer.setCircleRadius(const(16.dp).compile(ExpressionContext.None))
      layer.setCircleColor(const(Color.Black))
      layer.setCircleOpacity(const(1.0f))
      style.addLayer(layer)

      val extent = BridgeMapFixture.DEFAULT_EXTENT
      val centerX = extent.physicalWidth / 2
      val centerY = extent.physicalHeight / 2

      fixture.pumpUntil("the initial point to render", PUMP_TIMEOUT) {
        fixture.hasRendered && fixture.readPixel(centerX, centerY).isNear(CIRCLE)
      }

      update(source, pointAt(FAR_AWAY))

      if (onDemand) {
        // The real hosts draw only the frames the session asks for. An unconditional pump would
        // mask an update that never turns into a frame request.
        fixture.settle()
        assertTrue(
          fixture.readPixel(centerX, centerY).isNear(BACKGROUND),
          "the update did not render without pumping: ${fixture.errors}",
        )
      } else {
        fixture.pumpUntil("the update to render", PUMP_TIMEOUT) {
          fixture.hasRendered && fixture.readPixel(centerX, centerY).isNear(BACKGROUND)
        }
      }
      assertEquals(emptyList(), fixture.errors, "the map should report nothing")
    }
  }

  private fun pointAt(position: Position): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      addFeature(geometry = Point(position))
    }

  private fun RgbaPixel.isNear(expected: RgbaPixel): Boolean =
    listOf(
        abs(expected.red - red),
        abs(expected.green - green),
        abs(expected.blue - blue),
        abs(expected.alpha - alpha),
      )
      .all { it <= CHANNEL_TOLERANCE }

  private companion object {
    const val SOURCE_ID = "points"
    const val LAYER_ID = "points-layer"
    const val CHANNEL_TOLERANCE = 2

    /** Matches the GeoJSON benchmark: 8000 points, one publication per frame. */
    const val STREAM_POINT_COUNT = 8_000

    const val FRAME_INTERVAL_MILLIS = 16L
    const val POLL_INTERVAL_MILLIS = 250L

    val STREAM_TIMEOUT = 30.seconds
    val PUMP_TIMEOUT = 30.seconds

    val ORIGIN = Position(longitude = 0.0, latitude = 0.0)

    /** At zoom 14 this is far beyond the viewport. */
    val FAR_AWAY = Position(longitude = 5.0, latitude = 5.0)

    val BACKGROUND = RgbaPixel(red = 0x33, green = 0x66, blue = 0x99, alpha = 0xff)
    val CIRCLE = RgbaPixel(red = 0x00, green = 0x00, blue = 0x00, alpha = 0xff)

    val STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#336699"}}
        ]}
        """
      )
  }
}
