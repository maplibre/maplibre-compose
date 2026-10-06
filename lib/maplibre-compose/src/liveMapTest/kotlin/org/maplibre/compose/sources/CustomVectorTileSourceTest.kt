package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.map.MapSnapshotRequest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.style.uninstall
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.captureWarnings
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.testing.withTestMapRuntime

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
          PointTile
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

  /**
   * MapLibre Native fails a whole still image on a tile error, so a snapshot draws a failed tile
   * empty instead, and both engines log the provider's exception.
   */
  @Test
  fun a_failing_mvt_provider_leaves_a_snapshot_with_its_other_tiles(): MapTestResult = runMapTest {
    val options = CustomVectorTileSourceOptions(minZoom = 0, maxZoom = 0)
    val working = CustomVectorTileSource("working", options) { PointTile }
    val failing =
      CustomVectorTileSource("failing", options) { error("provider failure for the test") }
    withTestMapRuntime { runtime ->
      val snapshotter =
        runtime.createSnapshotter(BlackStyle) {
          CircleLayer(
            id = "failing-points",
            source = failing,
            sourceLayer = SourceLayer,
            color = const(Color.Red),
            radius = const(16.dp),
          )
          CircleLayer(
            id = "working-points",
            source = working,
            sourceLayer = SourceLayer,
            color = const(Color.Blue),
            radius = const(16.dp),
          )
        }
      try {
        captureWarnings { warnings ->
          val image =
            snapshotter.capture(
              MapSnapshotRequest(
                size = DpSize(SnapshotSize.dp, SnapshotSize.dp),
                cameraPosition = CameraPosition(zoom = 0.0),
              )
            )

          assertEquals(Blue, image.readPixel(SnapshotSize / 2, SnapshotSize / 2))
          assertTrue(
            warnings.any { "'failing'" in it },
            "Expected a logged failure for source 'failing', got $warnings",
          )
        }
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
      }
    }
  }

  private fun ImageBitmap.readPixel(x: Int, y: Int): RgbaPixel {
    val pixel = IntArray(1)
    readPixels(buffer = pixel, startX = x, startY = y, width = 1, height = 1)
    val argb = pixel.single()
    return RgbaPixel(
      red = argb ushr 16 and 0xff,
      green = argb ushr 8 and 0xff,
      blue = argb and 0xff,
      alpha = argb ushr 24 and 0xff,
    )
  }

  private class CancellationState {
    @Volatile var started = false
    @Volatile var cancelled = false
  }

  private companion object {
    const val SourceId = "custom-vector"
    const val SourceLayer = "points"
    const val Center = 256
    const val SnapshotSize = 64
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

    /** One point feature with id 1 and `name = center`, in a layer named `points`. */
    val PointTile: ByteArray = protobuf {
      message(3) {
        string(1, SourceLayer)
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
