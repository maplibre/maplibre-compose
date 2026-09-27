package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.mlnffi.fileUrlOf
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.MlnFfiMapFixture
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.addSource
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection
import org.maplibre.spatialk.geojson.toJson

/** Sensitive engine coverage for an imperative GeoJSON update after installation. */
class GeoJsonSourceUpdateTest {
  @Test
  fun queued_url_then_inline_data_renders_the_newest_data_without_an_unrequested_frame():
    MapTestResult = assertQueuedUpdate(latestIsUri = false)

  @Test
  fun queued_inline_then_url_data_renders_the_newest_data(): MapTestResult =
    assertQueuedUpdate(latestIsUri = true)

  private fun assertQueuedUpdate(latestIsUri: Boolean): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(STYLE)
      fixture.state.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 14.0))
      val style = checkNotNull(fixture.style) { "Errors: ${fixture.errors}" }
      val source =
        GeoJsonSource(
          SOURCE_ID,
          GeoJsonData.Features(pointAt(ORIGIN)),
          GeoJsonOptions(),
        )
      fixture.state.style.addSource(source)
      val layer = TestLayer(LAYER_ID, "circle", source)
      layer.paint(
        "circle-radius",
        (const(16.dp).compile(ExpressionContext.None)).asLayerProperty(),
      )
      layer.paint("circle-color", (const(Color.Black)).asLayerProperty())
      layer.paint("circle-opacity", (const(1.0f)).asLayerProperty())
      style.install(layer)
      val sourceHandle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources[SOURCE_ID])

      val centerX = 256
      val centerY = 256
      fixture.pumpUntil("the initial point to render") {
        fixture.readPixel(centerX, centerY).isNear(CIRCLE)
      }

      val file = FfiTestPlatform.createCacheFile()
      try {
        SystemFileSystem.sink(file).buffered().use {
          it.writeString(pointAt(if (latestIsUri) FAR_AWAY else ORIGIN).toJson())
        }
        val uri = GeoJsonData.Uri(fileUrlOf(file))
        val inline = GeoJsonData.Features(pointAt(if (latestIsUri) ORIGIN else FAR_AWAY))
        (fixture as MlnFfiMapFixture).withOwnerParked {
          sourceHandle.asMutable!!.setData(if (latestIsUri) inline else uri)
          sourceHandle.asMutable!!.setData(if (latestIsUri) uri else inline)
        }

        (fixture.style as MlnFfiStyleBinding).awaitGeoJsonUpdates()
        if (latestIsUri) {
          // Completion includes submission, not loading the URL's contents.
          fixture.pumpUntil("the newer URL data to replace the inline submission") {
            fixture.readPixel(centerX, centerY).isNear(BACKGROUND)
          }
        } else {
          // Real hosts draw only requested frames. An unconditional pump could mask a missing
          // repaint.
          fixture.settle()
          assertTrue(
            fixture.readPixel(centerX, centerY).isNear(BACKGROUND),
            "the update did not render without pumping: ${fixture.errors}",
          )
        }
        assertEquals(emptyList(), fixture.errors, "the map should report nothing")
      } finally {
        FfiTestPlatform.deleteCacheFile(file)
      }
    }
  }

  @Test
  fun a_base_style_update_preserves_the_loaded_sources_minimum_zoom(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(MIN_ZOOM_STYLE)
      fixture.state.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 8.0))
      val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources[SOURCE_ID])
      fixture.pumpUntil("the source to render above its minimum zoom") {
        fixture.readPixel(256, 256).isNear(CIRCLE)
      }
      lateinit var completion: Deferred<Unit>
      (fixture as MlnFfiMapFixture).withOwnerParked {
        handle.asMutable!!.setData(GeoJsonData.Features(pointAt(FAR_AWAY)))
        completion =
          async(start = CoroutineStart.UNDISPATCHED) {
            (fixture.style as MlnFfiStyleBinding).awaitGeoJsonUpdates()
          }
        assertFalse(completion.isCompleted, "completion missed a queued source's first update")
      }
      completion.await()
      fixture.settle()
      assertTrue(fixture.readPixel(256, 256).isNear(BACKGROUND))
      fixture.state.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 6.0))
      fixture.pumpUntil("the source to stay hidden below its minimum zoom") {
        fixture.readPixel(256, 256).isNear(BACKGROUND)
      }

      handle.asMutable!!.setData(GeoJsonData.Features(pointAt(ORIGIN)))

      (fixture.style as MlnFfiStyleBinding).awaitGeoJsonUpdates()
      fixture.settle()
      assertTrue(fixture.readPixel(256, 256).isNear(BACKGROUND))
      fixture.state.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 8.0))
      fixture.pumpUntil("the updated point to render above its minimum zoom") {
        fixture.readPixel(256, 256).isNear(CIRCLE)
      }
      assertEquals(emptyList(), fixture.errors)
    }
  }

  @Test
  fun rejected_data_reports_a_source_event_keeps_the_previous_point_and_allows_recovery():
    MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(STYLE)
      fixture.state.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 14.0))
      val binding = fixture.style as MlnFfiStyleBinding
      val source =
        GeoJsonSource(
          SOURCE_ID,
          GeoJsonData.Features(pointAt(ORIGIN)),
          GeoJsonOptions(synchronousTiling = true),
        )
      val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.addSource(source))
      val layer = TestLayer(LAYER_ID, "circle", source)
      layer.paint("circle-radius", (const(16.dp).compile(ExpressionContext.None)).asLayerProperty())
      layer.paint("circle-color", (const(Color.Black)).asLayerProperty())
      binding.install(layer)
      fixture.pumpUntil("the initial point to render") {
        fixture.readPixel(256, 256).isNear(CIRCLE)
      }

      coroutineScope {
        val event =
          async(start = CoroutineStart.UNDISPATCHED) {
            fixture.state.events.filterIsInstance<MapEvent.SourceDataFailed>().first()
          }
        // Submission succeeds; parsing fails later on the worker.
        handle.asMutable!!.setData(GeoJsonData.JsonString("{invalid GeoJSON}"))
        assertTrue(runCatching { binding.awaitGeoJsonUpdates() }.isFailure)
        val failure = withTimeout(5_000) { event.await() }
        assertEquals(SOURCE_ID, failure.sourceId)
        assertTrue(failure.cause.message.orEmpty().isNotBlank())
      }
      assertTrue(fixture.readPixel(256, 256).isNear(CIRCLE))

      handle.asMutable!!.setData(GeoJsonData.Features(pointAt(FAR_AWAY)))
      binding.awaitGeoJsonUpdates()
      fixture.settle()
      assertTrue(fixture.readPixel(256, 256).isNear(BACKGROUND))
    }
  }

  private fun MlnFfiMapFixture.withOwnerParked(submit: () -> Unit) {
    val parked = TestLatch(1)
    val release = TestLatch(1)
    val finished = TestLatch(1)
    try {
      assertTrue(
        bridge.session.postOwnerTaskForTest {
          parked.countDown()
          release.await(5_000L)
          finished.countDown()
        }
      )
      assertTrue(parked.await(5_000L), "native owner did not reach the gate")
      submit()
      assertEquals(1L, finished.count, "submission waited for the busy native owner")
    } finally {
      release.countDown()
    }
  }

  private fun pointAt(position: Position): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      addFeature(geometry = Point(position))
    }

  private companion object {
    const val SOURCE_ID = "points"
    const val LAYER_ID = "points-layer"
    val ORIGIN = Position(0.0, 0.0)
    val FAR_AWAY = Position(5.0, 5.0)
    val BACKGROUND = RgbaPixel(0x33, 0x66, 0x99, 0xff)
    val CIRCLE = RgbaPixel(0x00, 0x00, 0x00, 0xff)
    val STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#336699"}}
        ]}
        """
          .trimIndent()
      )
    val MIN_ZOOM_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{"points":{"type":"geojson","minzoom":7,"data":{
          "type":"FeatureCollection","features":[{"type":"Feature","geometry":{
            "type":"Point","coordinates":[0,0]},"properties":{}}]}}},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#336699"}},
          {"id":"points-layer","type":"circle","source":"points","paint":{
            "circle-radius":16,"circle-color":"#000000"}}]}
        """
          .trimIndent()
      )
  }
}
