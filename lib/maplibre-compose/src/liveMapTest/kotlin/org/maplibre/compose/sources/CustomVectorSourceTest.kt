package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapLibreFlavor
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.mapLibreFlavor
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest

class CustomVectorSourceTest {

  @Test
  fun an_mvt_provider_renders_queries_and_retains_feature_state(): MapTestResult = runMapTest {
    val requests = RecordingList<TileCoordinate>()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertNotNull(fixture.style)
      val source =
        CustomVectorSource(SOURCE_ID, CustomVectorSourceOptions(minZoom = 0, maxZoom = 0)) { tile ->
          requests += tile
          POINT_TILE
        }
      style.addSource(source)
      val layer = CircleLayer("custom-vector-points", source)
      layer.sourceLayer = SOURCE_LAYER
      layer.setCircleRadius(const(48.dp).compile(ExpressionContext.None))
      layer.setCircleColor(const(Color.Blue).compile(ExpressionContext.None))
      style.addLayer(layer)

      fixture.pumpUntilPixel("the custom MVT point to render", CENTER, CENTER, BLUE)

      assertTrue(requests.isNotEmpty())
      assertEquals(TileCoordinate(zoomLevel = 0, x = 0, y = 0), requests.first())
      val features = source.querySourceFeatures(setOf(SOURCE_LAYER))
      assertEquals("center", features.single().properties?.get("name")?.jsonPrimitive?.content)

      source.setFeatureState(SOURCE_LAYER, FEATURE_ID, buildJsonObject { put("selected", true) })
      assertEquals(
        true,
        source.getFeatureState(SOURCE_LAYER, FEATURE_ID)["selected"]?.jsonPrimitive?.boolean,
      )
      source.removeFeatureState(SOURCE_LAYER, FEATURE_ID, "selected")
      assertEquals(null, source.getFeatureState(SOURCE_LAYER, FEATURE_ID)["selected"])
      source.setFeatureState(SOURCE_LAYER, FEATURE_ID, buildJsonObject { put("selected", true) })
      source.resetFeatureStates(SOURCE_LAYER)
      assertEquals(emptySet(), source.getFeatureState(SOURCE_LAYER, FEATURE_ID).keys)

      val answered = requests.size
      if (mapLibreFlavor == MapLibreFlavor.NATIVE) {
        source.invalidateTile(TileCoordinate(zoomLevel = 0, x = 0, y = 0))
        fixture.pumpUntil("the invalidated custom MVT tile to be requested again") {
          requests.size > answered
        }
      } else {
        assertFailsWith<UnsupportedOperationException> {
          source.invalidateTile(TileCoordinate(zoomLevel = 0, x = 0, y = 0))
        }
        assertEquals(answered, requests.size, "browser invalidation must not reload the source")
      }
    }
  }

  @Test
  fun replacing_the_style_cancels_an_mvt_provider_call(): MapTestResult = runMapTest {
    val state = CancellationState()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertNotNull(fixture.style)
      val source =
        CustomVectorSource(SOURCE_ID, CustomVectorSourceOptions(minZoom = 0, maxZoom = 0)) {
          state.started = true
          try {
            awaitCancellation()
          } finally {
            state.cancelled = true
          }
        }
      style.addSource(source)
      val layer = CircleLayer("custom-vector-points", source)
      layer.sourceLayer = SOURCE_LAYER
      style.addLayer(layer)
      fixture.pumpUntil("the custom MVT provider to start") { state.started }

      fixture.loadStyle(REPLACEMENT_STYLE)

      fixture.pumpUntil("the detached custom MVT provider to be cancelled") { state.cancelled }
    }
  }

  @Test
  fun removing_the_source_cancels_an_mvt_provider_call(): MapTestResult = runMapTest {
    val state = CancellationState()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertNotNull(fixture.style)
      val source =
        CustomVectorSource(SOURCE_ID, CustomVectorSourceOptions(minZoom = 0, maxZoom = 0)) {
          state.started = true
          try {
            awaitCancellation()
          } finally {
            state.cancelled = true
          }
        }
      val layer = CircleLayer("custom-vector-points", source)
      layer.sourceLayer = SOURCE_LAYER
      style.addSource(source)
      style.addLayer(layer)
      fixture.pumpUntil("the custom MVT provider to start") { state.started }

      style.removeLayer(layer)
      style.removeSource(source)

      fixture.pumpUntil("the removed custom MVT provider to be cancelled") { state.cancelled }
    }
  }

  private class CancellationState {
    @Volatile var started = false
    @Volatile var cancelled = false
  }

  private companion object {
    const val SOURCE_ID = "custom-vector"
    const val SOURCE_LAYER = "points"
    const val FEATURE_ID = "1"
    const val CENTER = 256
    val BLUE = RgbaPixel(red = 0, green = 0, blue = 255, alpha = 255)

    val BLACK_STYLE =
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

    val REPLACEMENT_STYLE =
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
    val POINT_TILE: ByteArray = protobuf {
      message(3) {
        string(1, SOURCE_LAYER)
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
