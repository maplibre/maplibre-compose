package org.maplibre.compose.sources

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.PreparedImage
import org.maplibre.spatialk.geojson.Position

/** MapLibre accepts any four corners without validating which is which. */
class ImageSourceDrawTest {

  @Test
  fun a_bitmap_image_source_draws_its_pixels_at_the_corners_it_was_given(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BLACK_STYLE)
        val style = assertNotNull(fixture.style)

        val source = ImageSource("image", WESTERN_HALF, splitImage(64, Color.Red, Color.Green))
        val handle = assertIs<ImageSourceHandle>(fixture.state.style.sources.add(source))
        style.install(TestLayer("image-layer", "raster", source))

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

        assertNotNull(handle.asMutable).setImage(splitImage(64, Color.Green, Color.Red))
        fixture.pumpUntilPixel(
          "the replacement image's western half to be drawn",
          64,
          EQUATOR,
          GREEN,
        )
        assertTrue(fixture.readPixel(192, EQUATOR).isNear(RED))
      }
    }

  /** Premultiplying twice would draw a quarter of the red; not at all would draw all of it. */
  @Test
  fun a_translucent_image_source_blends_once(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertNotNull(fixture.style)

      val translucent = splitImage(64, Color(255, 0, 0, 128), Color(255, 0, 0, 128))
      val source = ImageSource("image", WESTERN_HALF, translucent)
      fixture.state.style.sources.add(source)
      style.install(
        TestLayer("image-layer", "raster", source).apply {
          paint("raster-fade-duration", JsonPrimitive(0))
        }
      )

      fixture.pumpUntilPixel("the half-alpha image over black", 64, EQUATOR, HALF_RED)
    }
  }

  @Test
  fun handle_writes_apply_in_call_order(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertNotNull(fixture.style)
      val red = splitImage(64, Color.Red, Color.Red)
      val green = splitImage(64, Color.Green, Color.Green)

      val source = ImageSource("image", WESTERN_HALF, green)
      val handle = fixture.state.style.sources.add(source)
      style.install(TestLayer("image-layer", "raster", source))
      handle.setImage(red)
      handle.setBounds(EASTERN_HALF)
      handle.setImage(green)

      fixture.pumpUntilPixel("the last image at the last bounds", 384, EQUATOR, GREEN)
      assertTrue(fixture.readPixel(128, EQUATOR).isNear(BLACK), "The first bounds were replaced")
    }
  }

  @Test
  fun a_prepared_image_is_reused_across_style_reloads(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val red = splitImage(64, Color.Red, Color.Red)
      for (baseStyle in listOf(BLACK_STYLE, OTHER_BLACK_STYLE)) {
        fixture.loadStyle(baseStyle)
        val source = ImageSource("image", WESTERN_HALF, red)
        fixture.state.style.sources.add(source)
        assertNotNull(fixture.style).install(TestLayer("image-layer", "raster", source))
        fixture.pumpUntilPixel("the prepared image in $baseStyle", 64, EQUATOR, RED)
      }
    }
  }

  private fun splitImage(size: Int, left: Color, right: Color): PreparedImage {
    val bitmap = ImageBitmap(size, size)
    val canvas = Canvas(bitmap)
    val half = size / 2f
    canvas.drawRect(Rect(0f, 0f, half, size.toFloat()), Paint().apply { color = left })
    canvas.drawRect(Rect(half, 0f, size.toFloat(), size.toFloat()), Paint().apply { color = right })
    return PreparedImage.fromBitmap(bitmap)
  }

  private companion object {
    const val EQUATOR = 256

    val RED = RgbaPixel(red = 255, green = 0, blue = 0, alpha = 255)
    val GREEN = RgbaPixel(red = 0, green = 255, blue = 0, alpha = 255)
    val BLACK = RgbaPixel(red = 0, green = 0, blue = 0, alpha = 255)
    val HALF_RED = RgbaPixel(red = 128, green = 0, blue = 0, alpha = 255)

    val WESTERN_HALF =
      PositionQuad(
        topLeft = Position(-180.0, 85.0),
        topRight = Position(0.0, 85.0),
        bottomRight = Position(0.0, -85.0),
        bottomLeft = Position(-180.0, -85.0),
      )

    val EASTERN_HALF =
      PositionQuad(
        topLeft = Position(0.0, 85.0),
        topRight = Position(180.0, 85.0),
        bottomRight = Position(180.0, -85.0),
        bottomLeft = Position(0.0, -85.0),
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

    val OTHER_BLACK_STYLE =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {},
          "layers": [
            { "id": "other-bg", "type": "background", "paint": { "background-color": "#000000" } }
          ]
        }
        """
          .trimIndent()
      )
  }
}
