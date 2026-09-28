package org.maplibre.compose.sources

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skiko.wasm.onWasmReady
import org.maplibre.compose.gljs.GlJsImageSource
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.PreparedImage
import org.maplibre.spatialk.geojson.Position
import web.images.ImageData

/** GL JS takes prepared pixels as `ImageData`; nothing encodes them to a URL. */
class BrowserImageSourceTest {

  @Test
  fun pixels_reach_the_source_during_the_call_without_a_url(): MapTestResult = runMapTest {
    awaitSkia()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertIs<GlJsStyleBinding>(fixture.style)
      fun source(): dynamic = style.withMap { it.getSource<GlJsImageSource>("image") }.asDynamic()

      val handle = fixture.state.style.sources.add(ImageSource("image", WORLD, solid(Color.Red)))
      val added = source()
      assertIs<ImageData>(added.image, "the pixels arrive with the source")
      assertEquals(255, added.image.data[0] as Int, "red")
      assertEquals(undefined, added.serialize().url, "no URL stands in for the pixels")

      handle.setImage(solid(Color.Green))
      val updated = source()
      assertEquals(0, updated.image.data[0] as Int, "no red after the update")
      assertEquals(255, updated.image.data[1] as Int, "green after the update")
    }
  }

  /** GL JS rebuilds a lost context from the serialized style, which has no image source pixels. */
  @Test
  fun pixels_survive_a_lost_webgl_context(): MapTestResult = runMapTest {
    awaitSkia()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertIs<GlJsStyleBinding>(fixture.style)
      val source = ImageSource("image", WORLD, solid(Color.Red))
      fixture.state.style.sources.add(source)
      style.install(
        TestLayer("image-layer", "raster", source).apply {
          paint("raster-fade-duration", JsonPrimitive(0))
        }
      )
      fixture.pumpUntilPixel("the image before context loss", 256, 256, RED)

      val map = assertNotNull(style.withMap { it })
      val extension =
        map.getCanvas().asDynamic().getContext("webgl2").getExtension("WEBGL_lose_context")
      assertNotNull(extension)
      var lost = false
      var restoredStyles = 0
      val lostSubscription = map.subscribe("webglcontextlost") { lost = true }
      val styleLoads = map.subscribe("style.load") { if (lost) restoredStyles++ }
      try {
        extension.loseContext()
        fixture.pumpUntil("context loss") { lost }
        extension.restoreContext()
        fixture.pumpUntil("the style rebuilt after context restoration") { restoredStyles > 0 }
        val restored = map.getSource<GlJsImageSource>("image").asDynamic()
        assertIs<ImageData>(restored.image, "the rebuilt source has its pixels again")
        fixture.pumpUntilPixel("the image after context restoration", 256, 256, RED)
      } finally {
        lostSubscription.cancel()
        styleLoads.cancel()
      }
    }
  }

  /** Skia backs every Compose bitmap in the browser; an earlier test may not have loaded it. */
  private suspend fun awaitSkia() =
    Promise<Unit> { resolve, _ -> onWasmReady { resolve(Unit) } }.await()

  private fun solid(color: Color): PreparedImage {
    val bitmap = ImageBitmap(8, 8)
    Canvas(bitmap).drawRect(Rect(0f, 0f, 8f, 8f), Paint().apply { this.color = color })
    return PreparedImage.fromBitmap(bitmap)
  }

  private companion object {
    val RED = RgbaPixel(red = 255, green = 0, blue = 0, alpha = 255)

    val WORLD =
      PositionQuad(
        topLeft = Position(-180.0, 85.0),
        topRight = Position(180.0, 85.0),
        bottomRight = Position(180.0, -85.0),
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
