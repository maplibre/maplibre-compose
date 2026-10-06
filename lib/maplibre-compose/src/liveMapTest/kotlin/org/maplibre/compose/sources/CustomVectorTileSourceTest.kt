package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogRecord
import org.maplibre.compose.logging.MapLogSource
import org.maplibre.compose.logging.MapLogger
import org.maplibre.compose.logging.MapLogging
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.style.uninstall
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest

class CustomVectorTileSourceTest {

  @Test
  fun an_mvt_provider_renders_its_tile(): MapTestResult = runMapTest {
    val requests = RecordingList<TileCoordinate>()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BlackStyle)
      val style = assertNotNull(fixture.style)
      val source =
        CustomVectorTileSource(
          SourceId,
          CustomVectorTileSourceOptions(minZoom = 0, maxZoom = 0),
        ) { tile ->
          requests += tile
          PointMvtTile
        }
      fixture.state.style.sources.add(source)
      val layer = TestLayer("custom-vector-points", "circle", source)
      layer.sourceLayer = SourceLayer
      layer.paint("circle-radius", (const(48.dp).compile(ExpressionContext.None)).asLayerProperty())
      layer.paint(
        "circle-color",
        (const(Color.Blue).compile(ExpressionContext.None)).asLayerProperty(),
      )
      style.install(layer)

      fixture.pumpUntilPixel("the custom MVT point to render", Center, Center, Blue)

      val handle = assertIs<VectorTileSourceHandle>(fixture.state.style.sources[SourceId])
      val features = handle.querySourceFeatures(setOf(SourceLayer))
      assertEquals(
        setOf("center"),
        features.map { it.properties?.get("name")?.jsonPrimitive?.content }.toSet(),
      )
      assertTrue(handle.querySourceFeatures(setOf("missing")).isEmpty())
      assertTrue(
        handle
          .querySourceFeatures(setOf(SourceLayer), feature["name"].asString() eq const("absent"))
          .isEmpty()
      )

      assertTrue(requests.isNotEmpty())
      assertEquals(TileCoordinate(zoomLevel = 0, x = 0, y = 0), requests.first())
    }
  }

  @Test
  fun a_failing_mvt_provider_logs_its_exception_once_and_fails_the_tile(): MapTestResult =
    runMapTest {
      val failure = IllegalStateException("fixture provider failure")
      val calls = RecordingList<TileCoordinate>()
      val records = RecordingList<MapLogRecord>()
      val previous = MapLogging.logger
      MapLogging.logger = MapLogger { record ->
        records += record
        previous?.log(record)
      }
      try {
        createMapFixture().use { fixture ->
          fixture.loadStyle(BlackStyle)
          val style = assertNotNull(fixture.style)
          val source =
            CustomVectorTileSource(
              SourceId,
              CustomVectorTileSourceOptions(minZoom = 0, maxZoom = 0),
            ) { tile ->
              calls += tile
              throw failure
            }
          style.install(source)
          val layer = TestLayer("custom-vector-points", "circle", source)
          layer.sourceLayer = SourceLayer
          style.install(layer)

          fixture.pumpUntil("MapLibre to report the failed tile") {
            records.any { it.source != MapLogSource.Library && failure.message!! in it.message }
          }

          val logged = records.filter { it.throwable === failure }
          assertEquals(calls.size, logged.size, "one record per failed provider call")
          val record = logged.first()
          assertEquals(MapLogSource.Library, record.source)
          assertEquals(MapLogLevel.Warning, record.level)
          assertTrue(SourceId in record.message, "the record names the source: ${record.message}")
        }
      } finally {
        MapLogging.logger = previous
      }
    }

  @Test
  fun replacing_the_style_cancels_an_mvt_provider_call(): MapTestResult = runMapTest {
    val state = CancellationState()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BlackStyle)
      val style = assertNotNull(fixture.style)
      val source =
        CustomVectorTileSource(SourceId, CustomVectorTileSourceOptions(minZoom = 0, maxZoom = 0)) {
          state.started = true
          try {
            awaitCancellation()
          } finally {
            state.cancelled = true
          }
        }
      style.install(source)
      val layer = TestLayer("custom-vector-points", "circle", source)
      layer.sourceLayer = SourceLayer
      style.install(layer)
      fixture.pumpUntil("the custom MVT provider to start") { state.started }

      fixture.loadStyle(ReplacementStyle)

      fixture.pumpUntil("the detached custom MVT provider to be cancelled") { state.cancelled }
    }
  }

  @Test
  fun removing_the_source_cancels_an_mvt_provider_call(): MapTestResult = runMapTest {
    val state = CancellationState()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BlackStyle)
      val style = assertNotNull(fixture.style)
      val source =
        CustomVectorTileSource(SourceId, CustomVectorTileSourceOptions(minZoom = 0, maxZoom = 0)) {
          state.started = true
          try {
            awaitCancellation()
          } finally {
            state.cancelled = true
          }
        }
      val layer = TestLayer("custom-vector-points", "circle", source)
      layer.sourceLayer = SourceLayer
      style.install(source)
      style.install(layer)
      fixture.pumpUntil("the custom MVT provider to start") { state.started }

      style.uninstall(layer)
      style.uninstall(source)

      fixture.pumpUntil("the removed custom MVT provider to be cancelled") { state.cancelled }
    }
  }

  private class CancellationState {
    @Volatile var started = false
    @Volatile var cancelled = false
  }

  private companion object {
    const val SourceId = "custom-vector"
    const val SourceLayer = "points"
    const val Center = 256
    val Blue = RgbaPixel(red = 0, green = 0, blue = 255, alpha = 255)

    val BlackStyle =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "name": "custom-vector-test",
          "sources": {},
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "#000000" } }
          ]
        }
        """
          .trimIndent()
      )

    val ReplacementStyle =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "name": "custom-vector-replacement",
          "sources": {},
          "layers": []
        }
        """
          .trimIndent()
      )
  }
}

/** One point feature with id 1 and `name = center`, in a layer named `points`. */
internal val PointMvtTile: ByteArray = protobuf {
  message(3) {
    string(1, "points")
    message(2) {
      varint(1, 1)
      packed(2, 0, 0)
      varint(3, 1)
      packed(4, 9, 4096, 4096)
    }
    string(3, "name")
    message(4) { string(1, "center") }
    varint(5, 4096)
    varint(15, 2)
  }
}

private fun protobuf(block: ProtobufBuilder.() -> Unit): ByteArray =
  ProtobufBuilder().apply(block).toByteArray()

private class ProtobufBuilder {
  private val bytes = mutableListOf<Byte>()

  fun varint(field: Int, value: Int) {
    unsigned((field shl 3).toLong())
    unsigned(value.toLong())
  }

  fun string(field: Int, value: String) {
    data(field, value.encodeToByteArray())
  }

  fun packed(field: Int, vararg values: Int) {
    val packed = ProtobufBuilder().apply { values.forEach { unsigned(it.toLong()) } }.toByteArray()
    data(field, packed)
  }

  fun message(field: Int, block: ProtobufBuilder.() -> Unit) {
    data(field, ProtobufBuilder().apply(block).toByteArray())
  }

  fun toByteArray(): ByteArray = bytes.toByteArray()

  private fun data(field: Int, value: ByteArray) {
    unsigned(((field shl 3) or 2).toLong())
    unsigned(value.size.toLong())
    bytes += value.toList()
  }

  private fun unsigned(value: Long) {
    var remaining = value
    while (remaining >= 0x80) {
      bytes += ((remaining and 0x7F) or 0x80).toByte()
      remaining = remaining ushr 7
    }
    bytes += remaining.toByte()
  }
}
