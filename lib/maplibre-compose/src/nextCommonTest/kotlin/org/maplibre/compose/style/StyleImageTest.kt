package org.maplibre.compose.style

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.ImageResizeOptions

class StyleImageTest {

  @Test
  fun an_icon_is_drawn_at_the_size_its_scale_implies(): MapTestResult = runMapTest {
    assertIconIsDrawn(MapFixture.DEFAULT_EXTENT, imageSize = 32)
  }

  @Test
  fun an_icon_on_a_retina_map_is_drawn_at_the_same_logical_size(): MapTestResult = runMapTest {
    assertIconIsDrawn(MapFixture.RETINA_EXTENT, imageSize = 64)
  }

  /**
   * Insets that cross over leave MapLibre a stretch box it would divide by zero over, so the image
   * is uploaded whole.
   */
  @Test
  fun an_icon_whose_insets_leave_nothing_to_stretch_is_still_drawn(): MapTestResult = runMapTest {
    assertIconIsDrawn(
      MapFixture.DEFAULT_EXTENT,
      imageSize = 32,
      resizeOptions = ImageResizeOptions(left = 20.dp, top = 4.dp, right = 20.dp, bottom = 4.dp),
    )
  }

  /**
   * The browser hands MapLibre straight RGBA and lets WebGL premultiply on upload, where every
   * other platform premultiplies itself; doing both would darken the icon to a quarter.
   */
  @Test
  fun a_translucent_icon_is_premultiplied_exactly_once(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(ICON_STYLE)
      val style = assertNotNull(fixture.style)
      style.addImage(
        IMAGE_ID,
        solidBitmap(32, Color.Red.copy(alpha = 0.5f)),
        sdf = false,
        resizeOptions = null,
      )

      val center = MapFixture.DEFAULT_EXTENT.physicalWidth / 2
      fixture.pumpUntilPixel(
        "the icon to be drawn half-strength over black",
        center,
        center,
        RgbaPixel(red = 128, green = 0, blue = 0, alpha = 255),
      )
    }
  }

  private suspend fun assertIconIsDrawn(
    extent: MapExtent,
    imageSize: Int,
    resizeOptions: ImageResizeOptions? = null,
  ) {
    createMapFixture(extent).use { fixture ->
      fixture.loadStyle(ICON_STYLE)
      val style = assertNotNull(fixture.style)
      style.addImage(IMAGE_ID, solidBitmap(imageSize, Color.Red), sdf = false, resizeOptions)

      val center = extent.physicalWidth / 2
      // MapLibre draws a style image at `pixels / pixelRatio` logical points, so both cases come
      // out 32 logical points wide.
      val halfWidth = (32 * extent.scaleFactor / 2).toInt()
      fixture.pumpUntilPixel("the icon to be drawn", center, center, RED)

      assertTrue(
        fixture.readPixel(center + halfWidth - INSIDE, center).isNear(RED),
        "The icon should still cover $INSIDE physical pixels inside its right edge",
      )
      assertTrue(
        fixture.readPixel(center + halfWidth + OUTSIDE, center).isNear(BLACK),
        "The icon should not reach $OUTSIDE physical pixels past its right edge, which is what a " +
          "wrong pixel ratio looks like",
      )
    }
  }

  private fun solidBitmap(size: Int, color: Color): ImageBitmap {
    val bitmap = ImageBitmap(size, size)
    Canvas(bitmap)
      .drawRect(Rect(0f, 0f, size.toFloat(), size.toFloat()), Paint().apply { this.color = color })
    return bitmap
  }

  private companion object {
    const val IMAGE_ID = "probe"

    /** Far enough from the icon's edge that filtering and sub-pixel placement cannot reach. */
    const val INSIDE = 6
    const val OUTSIDE = 8

    val RED = RgbaPixel(red = 255, green = 0, blue = 0, alpha = 255)
    val BLACK = RgbaPixel(red = 0, green = 0, blue = 0, alpha = 255)

    /** One point at the origin, which at zoom 0 is the middle of the viewport. */
    val ICON_STYLE =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {
            "point": {
              "type": "geojson",
              "data": {
                "type": "Feature",
                "properties": {},
                "geometry": { "type": "Point", "coordinates": [0, 0] }
              }
            }
          },
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "#000000" } },
            {
              "id": "icon",
              "type": "symbol",
              "source": "point",
              "layout": {
                "icon-image": "$IMAGE_ID",
                "icon-allow-overlap": true,
                "icon-ignore-placement": true
              }
            }
          ]
        }
        """
          .trimIndent()
      )
  }
}
