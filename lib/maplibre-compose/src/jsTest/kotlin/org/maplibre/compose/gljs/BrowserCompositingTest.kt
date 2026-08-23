package org.maplibre.compose.gljs

import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceOrigin
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.style.BaseStyle

private const val FULL = GPU_CANVAS_SIZE
private const val SMALL = FULL / 2
private const val INSET = FULL / 4
private const val FRACTIONAL_SCALE = 1.7

private const val RED = "#ff0000"
private const val BLUE = "#0000ff"
private const val PAGE = "#101014"
private const val CANVAS = "#00ff00"
private const val PAGE_ARGB = 0xff101014.toInt()

private val SPLIT_STYLE =
  BaseStyle.Json(
    """
    {
      "version": 8,
      "name": "compositing",
      "sources": {
        "shape": {
          "type": "geojson",
          "data": {
            "type": "Feature",
            "properties": {},
            "geometry": {
              "type": "Polygon",
              "coordinates": [[[-180, -85], [0, -85], [0, 85], [-180, 85], [-180, -85]]]
            }
          }
        }
      },
      "layers": [
        {"id": "bg", "type": "background", "paint": {"background-color": "#0000ff"}},
        {"id": "shape", "type": "fill", "source": "shape", "paint": {"fill-color": "#ff0000"}}
      ]
    }
    """
      .trimIndent()
  )

private val HEATMAP_STYLE =
  BaseStyle.Json(
    """
    {
      "version": 8,
      "name": "heatmap compositing",
      "sources": {
        "point": {
          "type": "geojson",
          "data": {
            "type": "Point",
            "coordinates": [0, 0]
          }
        }
      },
      "layers": [
        {"id": "bg", "type": "background", "paint": {"background-color": "#0000ff"}},
        {
          "id": "heatmap",
          "type": "heatmap",
          "source": "point",
          "paint": {
            "heatmap-radius": 20,
            "heatmap-color": [
              "interpolate",
              ["linear"],
              ["heatmap-density"],
              0, "rgba(0, 0, 0, 0)",
              0.01, "#ff0000",
              1, "#ff0000"
            ]
          }
        }
      ]
    }
    """
      .trimIndent()
  )

class BrowserCompositingTest {

  @Test
  fun the_map_lands_in_the_callers_framebuffer_and_never_on_the_canvas() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    GlJsRenderTarget(gl, FULL, FULL, generation = 1).use { target ->
      CompositedMap(SPLIT_STYLE).use { map ->
        map.drawTheWholeStyle(target)

        // One task, no yield: nothing promises the drawing buffer survives a turn of the event
        // loop.
        gl.bindFramebuffer(gl.FRAMEBUFFER, null)
        gl.disable(gl.SCISSOR_TEST)
        gl.colorMask(true, true, true, true)
        gl.clearColor(0, 1, 0, 1)
        gl.clear(gl.COLOR_BUFFER_BIT)
        assertTrue(map.drawOnce(target), "the map should have drawn one more frame")

        assertEquals(
          mapOf(CANVAS to FULL * FULL),
          histogram(readFramebuffer(gl, null, FULL, FULL)),
          "the canvas should still hold only what the test cleared it to",
        )
        assertEquals(
          mapOf(RED to FULL * FULL / 2, BLUE to FULL * FULL / 2),
          histogram(readFramebuffer(gl, target.framebuffer, FULL, FULL)),
          "the map should have drawn into the framebuffer it was handed",
        )
      }
    }
  }

  @Test
  fun the_two_colour_style_splits_the_target_down_the_prime_meridian() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    GlJsRenderTarget(gl, FULL, FULL, generation = 1).use { target ->
      CompositedMap(SPLIT_STYLE).use { map ->
        map.drawTheWholeStyle(target)
        assertEquals(
          mapOf(RED to FULL * FULL / 2, BLUE to FULL * FULL / 2),
          histogram(readFramebuffer(gl, target.framebuffer, FULL, FULL)),
          "the world should fill the target, red west of the prime meridian and blue east",
        )
      }
    }
  }

  @Test
  fun a_heatmap_uses_the_map_target_size_instead_of_the_shared_canvas_size() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    GlJsRenderTarget(gl, SMALL, SMALL, generation = 1).use { target ->
      CompositedMap(HEATMAP_STYLE, scaleFactor = FRACTIONAL_SCALE).use { map ->
        val extent = MapExtent.fromPhysical(SMALL, SMALL, FRACTIONAL_SCALE)
        map.drawUntil(target, "the heatmap point to reach the render tree") {
          map.rendersFeature("heatmap", extent.width / 2, extent.height / 2)
        }

        val pixels = readFramebuffer(gl, target.framebuffer, SMALL, SMALL)
        val center = (SMALL / 2 * SMALL + SMALL / 2) * 4
        assertTrue(
          pixels[center].toInt() and 0xff > pixels[center + 2].toInt() and 0xff,
          "the central heatmap point should be red rather than the blue background",
        )
        assertEquals(
          FULL,
          gl.drawingBufferWidth.unsafeCast<Int>(),
          "the map draw should restore the shared canvas drawing buffer size",
        )
      }
    }
  }

  @Test
  fun skia_draws_the_adopted_texture_into_a_gpu_surface() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    GlJsRenderTarget(gl, FULL, FULL, generation = 1).use { target ->
      CompositedMap(SPLIT_STYLE).use { map -> map.drawTheWholeStyle(target) }

      val surface =
        Surface.makeRenderTarget(
          gpu.skia,
          false,
          ImageInfo(FULL, FULL, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
          0,
          SurfaceOrigin.TOP_LEFT,
          null,
          false,
        )
      val bitmap = Bitmap()
      try {
        surface.canvas.clear(PAGE_ARGB)
        surface.canvas.drawImageRect(
          target.image,
          Rect.makeWH(FULL.toFloat(), FULL.toFloat()),
          Rect.makeXYWH(INSET.toFloat(), INSET.toFloat(), SMALL.toFloat(), SMALL.toFloat()),
          SamplingMode.LINEAR,
          null,
          strict = true,
        )
        gpu.skia.flush(surface)
        gpu.skia.submit(true)

        bitmap.allocPixels(ImageInfo(FULL, FULL, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
        assertTrue(surface.readPixels(bitmap, 0, 0), "the GPU surface should read back")
        assertEquals(
          mapOf(
            PAGE to FULL * FULL - SMALL * SMALL,
            RED to SMALL * SMALL / 2,
            BLUE to SMALL * SMALL / 2,
          ),
          histogram(checkNotNull(bitmap.readPixels())),
          "Skia should sample MapLibre's texture into exactly the rect it was drawn to",
        )
      } finally {
        bitmap.close()
        surface.close()
      }
    }
  }

  @Test
  fun a_resize_allocates_a_new_target_and_the_map_keeps_drawing() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    ComposeGlJsCompositor(logger = null).use { compositor ->
      CompositedMap(SPLIT_STYLE).use { map ->
        val first =
          assertIs<GlJsFrameTarget.Composited>(
              compositor.acquire(MapExtent.fromPhysical(FULL, FULL, 1.0))
            )
            .target
        map.drawTheWholeStyle(first)

        val second =
          assertIs<GlJsFrameTarget.Composited>(
              compositor.acquire(MapExtent.fromPhysical(SMALL, SMALL, 1.0))
            )
            .target
        assertNotEquals(first.generation, second.generation, "a resize should mint a new target")
        assertEquals(SMALL, second.widthPx)

        map.drawTheWholeStyle(second)
        assertEquals(
          mapOf(RED to SMALL * SMALL / 2, BLUE to SMALL * SMALL / 2),
          histogram(readFramebuffer(gl, second.framebuffer, SMALL, SMALL)),
          "the map should have gone on drawing, into the new target",
        )
      }
    }
  }

  @Test
  fun closing_a_composited_map_leaves_the_shared_context_alive() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    GlJsRenderTarget(gl, FULL, FULL, generation = 1).use { target ->
      CompositedMap(SPLIT_STYLE).use { map -> map.drawTheWholeStyle(target) }
      assertFalse(
        gl.isContextLost().unsafeCast<Boolean>(),
        "removing the map took the context every other renderer on the page shares",
      )
    }
  }
}

private fun gpuTest(block: suspend (BrowserGpu) -> Unit): Promise<*> =
  MainScope().promise { block(browserGpu()) }

private suspend fun CompositedMap.drawTheWholeStyle(target: GlJsRenderTarget) {
  drawUntil(target, "the red polygon to reach the render tree") {
    rendersFeature("shape", target.widthPx / 4, target.heightPx / 2)
  }
}
