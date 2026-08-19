package org.maplibre.compose.style

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.ImageResizeOptions
import org.maplibre.compose.util.PositionQuad
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
      attachProbeIcon(
        fixture,
        MapFixture.DEFAULT_EXTENT,
        solidBitmap(32, Color.Red.copy(alpha = 0.5f)),
      )

      val center = MapFixture.DEFAULT_EXTENT.physicalWidth / 2
      fixture.pumpUntilIconPixel(
        "the icon to be drawn half-strength over black",
        MapFixture.DEFAULT_EXTENT,
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
      attachProbeIcon(fixture, extent, solidBitmap(imageSize, Color.Red), resizeOptions)

      val center = extent.physicalWidth / 2
      // MapLibre draws a style image at `pixels / pixelRatio` logical points, so both cases come
      // out 32 logical points wide.
      val halfWidth = (32 * extent.scaleFactor / 2).toInt()
      fixture.pumpUntilIconPixel("the icon to be drawn", extent, center, center, RED)

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
   * An image source is drawn first, northwest of the origin, so a black icon pixel can be told
   * apart from a bitmap that never reached MapLibre.
   *
   * The style image is uploaded, a frame is drawn, and then `icon-image` is set on the live layer.
   * GLES keeps a miss from creation JSON that names an image that is not in the style yet. The
   * `image` expression checks the atlas when it evaluates; GLES packs a runtime image when a layout
   * requests that name, so `["image","probe"]` can stay unresolved. The name as a string is what
   * that layout requests.
   */
  private suspend fun attachProbeIcon(
    fixture: MapFixture,
    extent: MapExtent,
    bitmap: ImageBitmap,
    resizeOptions: ImageResizeOptions? = null,
  ) {
    fixture.loadStyle(BLACK_STYLE)
    val style = assertNotNull(fixture.style)

    val raster = ImageSource("raster", NORTHWEST_QUARTER, solidBitmap(32, Color.Red))
    style.addSource(raster)
    style.addLayer(RasterLayer("raster", raster))
    val controlX = (CONTROL_X * extent.scaleFactor).toInt()
    val controlY = (CONTROL_Y * extent.scaleFactor).toInt()
    fixture.pumpUntilPixel("the image-source control to be drawn", controlX, controlY, RED)

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
    layer.setIconImage(const(IMAGE_ID).compile(ExpressionContext.None).cast())
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

    /**
     * Logical pixels inside the northwest quarter of the world at zoom 0, away from the icon at the
     * origin.
     */
    const val CONTROL_X = 64
    const val CONTROL_Y = 128

    val RED = RgbaPixel(red = 255, green = 0, blue = 0, alpha = 255)
    val BLACK = RgbaPixel(red = 0, green = 0, blue = 0, alpha = 255)

    val NORTHWEST_QUARTER =
      PositionQuad(
        topLeft = Position(-180.0, 85.0),
        topRight = Position(-90.0, 85.0),
        bottomRight = Position(-90.0, 0.0),
        bottomLeft = Position(-180.0, 0.0),
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

private suspend fun MapFixture.pumpUntilIconPixel(
  description: String,
  extent: MapExtent,
  x: Int,
  y: Int,
  expected: RgbaPixel,
  timeout: Duration = 30.seconds,
) {
  val deadline = TimeSource.Monotonic.markNow() + timeout
  var pixel = readPixel(x, y)
  while (!pixel.isNear(expected)) {
    if (!deadline.hasNotPassedNow()) {
      val center = DpOffset((extent.width / 2).dp, (extent.height / 2).dp)
      val hits = session.queryRenderedFeatures(offset = center, layerIds = setOf("icon"))
      val layerJson = (style?.getLayer("icon") as? UnknownLayer)?.definition
      error(
        "Timed out waiting for $description: ($x, $y) was $pixel, not $expected. " +
          "Hits: ${hits.size}. Layer JSON: $layerJson. Errors: $errors"
      )
    }
    pump(frames = 1)
    pixel = readPixel(x, y)
  }
}
