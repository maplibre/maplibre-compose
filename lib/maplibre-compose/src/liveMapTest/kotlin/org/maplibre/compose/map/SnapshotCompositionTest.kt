package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.rememberFont
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class SnapshotCompositionTest {
  @Test
  fun snapshot_waits_for_painter_pixels_and_the_compiled_image_property() = runTest {
    val adapter =
      FakeSnapshotterAdapter(
        capture = { _, revision ->
          val captured = revision.images.single()
          val layout = revision.layers.single().definition.value["layout"] as JsonObject
          assertEquals(
            JsonArray(listOf(JsonPrimitive("image"), JsonPrimitive(captured.id))),
            layout["icon-image"],
          )
          captured.image.toImageBitmap()
        }
      )
    val runtime = mapRuntimeForTest(createSnapshotterAdapter = { adapter })
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val painter = ColorPainter(Color.Red)
    try {
      val snapshotter =
        runtime.createSnapshotter(BaseStyle.Empty) {
          SymbolLayer(
            id = "pin",
            source = source,
            iconImage = image(painter, size = DpSize(4.dp, 4.dp)),
          )
        }
      val bitmap = snapshotter.capture(MapSnapshotRequest(4, 4))
      val pixels = IntArray(16)
      bitmap.readPixels(pixels)
      assertEquals(List(16) { 0xffff0000.toInt() }, pixels.toList())
    } finally {
      runtime.close()
      runtime.awaitClosed()
    }
  }

  @Test
  fun snapshot_waits_for_a_remembered_font_file() = runTest {
    val fonts = mutableListOf<List<String>>()
    val adapter =
      FakeSnapshotterAdapter(
        capture = { request, revision ->
          fonts += revision.fonts.map { it.name }
          FakeImageBitmap(request.width, request.height)
        }
      )
    val runtime = mapRuntimeForTest(createSnapshotterAdapter = { adapter })
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val snapshotter =
      runtime.createSnapshotter(BaseStyle.Empty) {
        SymbolLayer(
          id = "labels",
          source = source,
          textFont =
            rememberFont("Body", emptyList(), source = "file") {
              yield()
              byteArrayOf(1, 2, 3)
            },
        )
      }

    snapshotter.capture(MapSnapshotRequest(1, 1))

    assertEquals(listOf(listOf("Body")), fonts)
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun snapshot_content_reads_the_viewport_of_its_own_request() = runTest {
    val sizes = mutableListOf<DpSize?>()
    val runtime = mapRuntimeForTest(createSnapshotterAdapter = { FakeSnapshotterAdapter() })
    val snapshotter =
      runtime.createSnapshotter(BaseStyle.Empty) { sizes += LocalViewport.current?.size }

    snapshotter.capture(MapSnapshotRequest(width = 30, height = 20))

    assertEquals(setOf(DpSize(30.dp, 20.dp)), sizes.toSet())
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun snapshot_content_has_no_map_state() = runTest {
    val states = mutableListOf<MapState?>()
    val runtime = mapRuntimeForTest(createSnapshotterAdapter = { FakeSnapshotterAdapter() })
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty) { states += LocalMapState.current }

    snapshotter.capture(MapSnapshotRequest(1, 1))

    assertEquals(listOf<MapState?>(null), states)
    runtime.close()
    runtime.awaitClosed()
  }
}
