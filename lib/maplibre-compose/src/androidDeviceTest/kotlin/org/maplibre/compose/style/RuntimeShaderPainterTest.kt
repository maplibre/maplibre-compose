package org.maplibre.compose.style

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.FakeSnapshotterAdapter
import org.maplibre.compose.map.MapSnapshotRequest
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.testing.composeStyle
import org.maplibre.compose.testing.runGraphicsTest
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class RuntimeShaderPainterTest {
  @Test
  fun shader_painter_reaches_style_as_readable_pixels() {
    assumeTrue("RuntimeShader requires API 33", Build.VERSION.SDK_INT >= 33)
    runGraphicsTest { graphics ->
      val painter = shaderPainter()
      val source =
        GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
      var captured: ImageSnapshot? = null
      composeStyle(
        graphicsContext = graphics,
        awaitRevision = { it.images.size == 1 },
        onRevision = { revision -> captured = revision.images.singleOrNull()?.image ?: captured },
      ) {
        SymbolLayer(id = "shader", source = source, iconImage = image(painter))
      }
      val bitmap = requireNotNull(captured).toImageBitmap()
      val pixels = IntArray(16)
      bitmap.readPixels(pixels)
      assertEquals(List(16) { 0xffff0000.toInt() }, pixels.toList())
    }
  }

  @Test
  fun shader_painter_works_in_a_snapshot_without_a_ui_owner() {
    assumeTrue("RuntimeShader requires API 33", Build.VERSION.SDK_INT >= 33)
    runTest {
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
      val painter = shaderPainter()
      try {
        val snapshotter =
          runtime.createSnapshotter(BaseStyle.Empty) {
            SymbolLayer(id = "shader", source = source, iconImage = image(painter))
          }
        val pixels = IntArray(16)
        snapshotter.capture(MapSnapshotRequest(4, 4)).readPixels(pixels)
        assertEquals(List(16) { 0xffff0000.toInt() }, pixels.toList())
      } finally {
        runtime.close()
        runtime.awaitClosed()
      }
    }
  }

  private fun shaderPainter() =
    object : Painter() {
      override val intrinsicSize = Size(4f, 4f)
      private val brush =
        ShaderBrush(RuntimeShader("half4 main(float2 p) { return half4(1, 0, 0, 1); }"))

      override fun DrawScope.onDraw() = drawRect(brush)
    }
}
