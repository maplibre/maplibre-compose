package org.maplibre.compose.style

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.SymbolLayer
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
      val painter =
        object : Painter() {
          override val intrinsicSize = Size(4f, 4f)
          private val brush =
            ShaderBrush(RuntimeShader("half4 main(float2 p) { return half4(1, 0, 0, 1); }"))

          override fun DrawScope.onDraw() = drawRect(brush)
        }
      val source =
        GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
      var captured: ImageSnapshot? = null
      composeStyle(
        graphicsContext = graphics,
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
}
