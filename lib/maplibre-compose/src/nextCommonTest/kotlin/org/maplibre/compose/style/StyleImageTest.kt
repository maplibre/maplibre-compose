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
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.ImageResizeOptions
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

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
      attachProbeIcon(fixture, solidBitmap(32, Color.Red.copy(alpha = 0.5f)))

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
      attachProbeIcon(fixture, solidBitmap(imageSize, Color.Red), resizeOptions)

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

  /**
   * GLES keeps a miss from the JSON that creates a symbol layer, even after a later `addImage`. The
   * image is uploaded and a frame is drawn first, then `icon-image` is set on the live layer so
   * that name is absent from the creation JSON.
   */
  private suspend fun attachProbeIcon(
    fixture: MapFixture,
    bitmap: ImageBitmap,
    resizeOptions: ImageResizeOptions? = null,
  ) {
    fixture.loadStyle(BLACK_STYLE)
    val style = assertNotNull(fixture.style)
    style.addImage(IMAGE_ID, bitmap, sdf = false, resizeOptions)
    fixture.pump(frames = 1)

    val source =
      GeoJsonSource(
        id = "point",
        data =
          GeoJsonData.Features(
            buildFeatureCollection<Geometry, JsonObject?> {
              addFeature(geometry = Point(Position(0.0, 0.0))) {}
            }
          ),
        options = GeoJsonOptions(),
      )
    style.addSource(source)

    val layer = SymbolLayer("icon", source)
    layer.setIconAllowOverlap(const(true).compile(ExpressionContext.None))
    layer.setIconIgnorePlacement(const(true).compile(ExpressionContext.None))
    style.addLayer(layer)
    layer.setIconImage(image(IMAGE_ID).compile(ExpressionContext.None))
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
