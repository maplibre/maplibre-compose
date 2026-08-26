package org.maplibre.compose.sources

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.compose.layers.RasterLayerDescriptor
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.PositionQuad
import org.maplibre.spatialk.geojson.Position

/** MapLibre accepts any four corners without validating which is which. */
class ImageSourceDrawTest {

  @Test
  fun a_bitmap_image_source_draws_its_pixels_at_the_corners_it_was_given(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BLACK_STYLE)
        val style = assertNotNull(fixture.style)

        val source = ImageSource("image", WESTERN_HALF, splitBitmap(64, Color.Red, Color.Green))
        style.addSource(source)
        style.addLayer(RasterLayerDescriptor("image-layer", source))

        // The western half of the world fills the western half of the viewport at zoom 0, with the
        // image's own halves either side of a quarter in.
        fixture.pumpUntilPixel("the image's western half to be drawn", 64, EQUATOR, RED)
        assertTrue(
          fixture.readPixel(192, EQUATOR).isNear(GREEN),
          "The image's eastern half should be east of its middle; a swapped corner pair mirrors it",
        )
        assertTrue(
          fixture.readPixel(384, EQUATOR).isNear(BLACK),
          "The image should not reach past the corners it was given",
        )
      }
    }

  private fun splitBitmap(size: Int, left: Color, right: Color): ImageBitmap {
    val bitmap = ImageBitmap(size, size)
    val canvas = Canvas(bitmap)
    val half = size / 2f
    canvas.drawRect(Rect(0f, 0f, half, size.toFloat()), Paint().apply { color = left })
    canvas.drawRect(Rect(half, 0f, size.toFloat(), size.toFloat()), Paint().apply { color = right })
    return bitmap
  }

  private companion object {
    const val EQUATOR = 256

    val RED = RgbaPixel(red = 255, green = 0, blue = 0, alpha = 255)
    val GREEN = RgbaPixel(red = 0, green = 255, blue = 0, alpha = 255)
    val BLACK = RgbaPixel(red = 0, green = 0, blue = 0, alpha = 255)

    val WESTERN_HALF =
      PositionQuad(
        topLeft = Position(-180.0, 85.0),
        topRight = Position(0.0, 85.0),
        bottomRight = Position(0.0, -85.0),
        bottomLeft = Position(-180.0, -85.0),
      )

    val BLACK_STYLE =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {},
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "#000000" } }
          ]
        }
        """
          .trimIndent()
      )
  }
}
