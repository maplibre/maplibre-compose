package org.maplibre.compose.sources

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skiko.wasm.onWasmReady
import org.maplibre.compose.gljs.GlJsImageSource
import org.maplibre.compose.gljs.ProtocolResponse
import org.maplibre.compose.gljs.addProtocol
import org.maplibre.compose.gljs.removeProtocol
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.style.SourceInstallation
import org.maplibre.compose.style.StyleMutationException
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

      handle.setUri(GREEN_IMAGE)
      handle.setImage(solid(Color.Green))
      val updated = source()
      assertEquals(0, updated.image.data[0] as Int, "no red after the update")
      assertEquals(255, updated.image.data[1] as Int, "green after the update")
    }
  }

  /** GL JS rebuilds a lost context from the serialized style, which has no image source pixels. */
  @Test
  fun pixels_survive_url_replacements_and_lost_webgl_context(): MapTestResult = runMapTest {
    awaitSkia()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertIs<GlJsStyleBinding>(fixture.style)
      val source = ImageSource("image", WORLD, solid(Color.Red))
      val handle = fixture.state.style.sources.add(source)
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
      var replacementFailed = false
      val failures = map.subscribe("error") { if (it.sourceId == "image") replacementFailed = true }
      try {
        handle.setUri("data:image/png;base64,aW52YWxpZA==")
        fixture.pumpUntil("the failed replacement URL") { replacementFailed }
        fixture.pumpUntilPixel("the old pixels after the rejected URL", 256, 256, RED)
      } finally {
        failures.cancel()
      }
      var lost = false
      var restoredStyles = 0
      val lostSubscription = map.subscribe("webglcontextlost") { lost = true }
      val styleLoads = map.subscribe("style.load") { if (lost) restoredStyles++ }
      suspend fun restoreContext() {
        lost = false
        val before = restoredStyles
        extension.loseContext()
        fixture.pumpUntil("context loss") { lost }
        extension.restoreContext()
        fixture.pumpUntil("the style rebuilt after context restoration") { restoredStyles > before }
      }
      try {
        restoreContext()
        val restored = map.getSource<GlJsImageSource>("image").asDynamic()
        assertIs<ImageData>(restored.image, "the rebuilt source has its pixels again")
        fixture.pumpUntilPixel("the image after context restoration", 256, 256, RED)

        // Hold the successful replacement until the style has been rebuilt with fallback pixels.
        val png = Base64.decode(GREEN_IMAGE.substringAfter(','))
        val bytes = Uint8Array<ArrayBuffer>(png.size)
        png.forEachIndexed { index, byte -> bytes.asDynamic()[index] = byte.toInt() and 0xFF }
        val response = unsafeJso<ProtocolResponse> { data = bytes.buffer }
        val pending = mutableListOf<() -> Unit>()
        var released = false
        addProtocol("test-image") { _, controller ->
          Promise { resolve, reject ->
            controller
              .asDynamic()
              .signal
              .addEventListener(
                "abort",
                {
                  reject(js("new DOMException('Aborted', 'AbortError')").unsafeCast<Throwable>())
                },
              )
            val respond = {
              if (controller.asDynamic().signal.aborted != true) resolve(response)
            }
            if (released) respond() else pending.add(respond)
          }
        }
        val urlOnly = fixture.state.style.sources.add(ImageSource("url-only", WORLD, GREEN_IMAGE))
        var urlOnlyLoads = 0
        val urlOnlyLoading =
          map.subscribe("sourcedataloading") {
            if (it.sourceId == "url-only") urlOnlyLoads++
          }
        try {
          handle.setUri("test-image://green")
          urlOnly.setUri("test-image://url-only")
          fixture.pumpUntil("the replacement requests to start") { pending.size >= 2 }
          restoreContext()
          released = true
          pending.forEach { it() }
          fixture.pumpUntil("the URL-only source to finish loading") {
            map.isSourceLoaded("url-only") == true
          }
          assertEquals(2, urlOnlyLoads, "only GL JS reloads the source without fallback pixels")
          fixture.pumpUntilPixel("the successful URL after context restoration", 256, 256, GREEN)
          restoreContext()
          fixture.pumpUntilPixel(
            "the successful URL after another context restoration",
            256,
            256,
            GREEN,
          )
        } finally {
          urlOnlyLoading.cancel()
          removeProtocol("test-image")
        }
      } finally {
        lostSubscription.cancel()
        styleLoads.cancel()
      }
    }
  }

  @Test
  fun rejected_bounds_keep_the_previous_coordinates_and_definition(): MapTestResult = runMapTest {
    awaitSkia()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertIs<GlJsStyleBinding>(fixture.style)
      val handle = fixture.state.style.sources.add(ImageSource("image", WORLD, solid(Color.Red)))
      val source = style.withMap { it.getSource<GlJsImageSource>("image") }.asDynamic()
      val before = js("JSON.stringify")(source.coordinates) as String

      val rejected = WORLD.copy(topLeft = Position(0.0, 91.0))
      handle.setBounds(rejected)

      assertEquals(before, js("JSON.stringify")(source.coordinates) as String)

      val image = solid(Color.Red)
      val initial = ImageSource("declarative", WORLD, image).definition()
      val installation = SourceInstallation(style, initial)
      val next = ImageSource("declarative", rejected, image).definition()
      repeat(2) {
        assertFailsWith<StyleMutationException> { installation.update(next) }
        assertEquals(initial, installation.definition)
      }
      val accepted =
        ImageSource("declarative", WORLD.copy(topLeft = Position(0.0, 80.0)), image).definition()
      installation.update(accepted)
      assertEquals(accepted, installation.definition)
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
    const val GREEN_IMAGE =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNg+M/wHwAEAQH/cetH5QAAAABJRU5ErkJggg=="
    val GREEN = RgbaPixel(red = 0, green = 255, blue = 0, alpha = 255)
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
