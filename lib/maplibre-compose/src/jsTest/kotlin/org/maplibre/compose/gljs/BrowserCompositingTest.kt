package org.maplibre.compose.gljs

import androidx.compose.ui.unit.DpOffset
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.browser.document
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
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.toDpOffset
import org.maplibre.spatialk.geojson.Position

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
    browserRenderTarget(FULL, FULL, generation = 1).use { target ->
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
    browserRenderTarget(FULL, FULL, generation = 1).use { target ->
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
    browserRenderTarget(SMALL, SMALL, generation = 1).use { target ->
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
    browserRenderTarget(FULL, FULL, generation = 1).use { target ->
      CompositedMap(SPLIT_STYLE).use { map -> map.drawTheWholeStyle(target) }

      assertEquals(
        mapOf(
          PAGE to FULL * FULL - SMALL * SMALL,
          RED to SMALL * SMALL / 2,
          BLUE to SMALL * SMALL / 2,
        ),
        drawTargetWithSkia(gpu.skia, target),
        "Skia should sample MapLibre's texture into exactly the rect it was drawn to",
      )
    }
  }

  @Test
  fun overdraw_is_rendered_in_the_first_requested_frame() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    browserRenderTarget(FULL, FULL, generation = 1).use { target ->
      CompositedMap(SPLIT_STYLE).use { map ->
        map.drawTheWholeStyle(target)
        val requestsBefore = map.frameRequests

        map.setOverdrawInspector(true)
        gl.enable(gl.SCISSOR_TEST)
        gl.scissor(0, 0, 1, 1)

        assertTrue(map.frameRequests > requestsBefore, "the option should request a frame")
        assertTrue(map.drawOnce(target), "the requested overdraw frame should render")
        assertFalse(
          gl.isEnabled(gl.SCISSOR_TEST).unsafeCast<Boolean>(),
          "MapLibre should not inherit Compose's damage scissor",
        )
        val colors = histogram(readFramebuffer(gl, target.framebuffer, FULL, FULL))
        assertFalse(
          RED in colors || BLUE in colors,
          "the first requested frame should use overdraw",
        )
        assertTrue(colors.keys.any { it != "#000000" }, "overdraw should record drawn fragments")
      }
    }
  }

  @Test
  fun a_new_skia_context_replaces_a_same_size_target_and_the_map_keeps_drawing() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    val compositor = ComposeGlJsCompositor(logger = null)
    CompositedMap(SPLIT_STYLE).use { map ->
      try {
        val first =
          assertIs<GlJsFrameTarget.Composited>(
              compositor.acquire(MapExtent.fromPhysical(FULL, FULL, 1.0))
            )
            .target
        map.drawTheWholeStyle(first)

        gpu.withRecreatedSkiaContext { nextSkia ->
          val second =
            assertIs<GlJsFrameTarget.Composited>(
                compositor.acquire(MapExtent.fromPhysical(FULL, FULL, 1.0))
              )
              .target
          assertNotEquals(
            first.generation,
            second.generation,
            "a new Skia context should replace a same-size target",
          )
          assertTrue(first.gl === second.gl, "the browser WebGL context should stay the same")

          map.drawTheWholeStyle(second)
          assertEquals(
            mapOf(RED to FULL * FULL / 2, BLUE to FULL * FULL / 2),
            histogram(readFramebuffer(gl, second.framebuffer, FULL, FULL)),
            "the existing map should draw into the replacement target",
          )
          assertEquals(
            mapOf(
              PAGE to FULL * FULL - SMALL * SMALL,
              RED to SMALL * SMALL / 2,
              BLUE to SMALL * SMALL / 2,
            ),
            drawTargetWithSkia(nextSkia, second),
            "the replacement Skia context should draw the replacement image",
          )
          compositor.close()
        }
      } finally {
        compositor.close()
      }
    }
  }

  @Test
  fun a_different_webgl_context_recreates_the_engine_and_replays_the_style() = gpuTest { gpu ->
    ComposeGlJsCompositor(logger = null).use { compositor ->
      CompositedMap(SPLIT_STYLE).use { map ->
        val extent = MapExtent.fromPhysical(FULL, FULL, 1.0)
        val first = assertIs<GlJsFrameTarget.Composited>(compositor.acquire(extent)).target
        map.drawTheWholeStyle(first)
        val firstEngine = assertNotNull(map.session.engineMapForTest())
        withNewWebGlContext { next ->
          assertTrue(gpu.gl !== next.gl)
          val second = assertIs<GlJsFrameTarget.Composited>(compositor.acquire(extent)).target
          assertFalse(map.drawOnce(second), "replacement must precede rendering in a new context")
          map.drawTheWholeStyle(second)
          assertTrue(firstEngine !== map.session.engineMapForTest())
          assertEquals(
            mapOf(RED to FULL * FULL / 2, BLUE to FULL * FULL / 2),
            histogram(readFramebuffer(next.gl.asDynamic(), second.framebuffer, FULL, FULL)),
          )
          compositor.close()
        }
      }
    }
  }

  @Test
  fun an_unsupported_extent_does_not_allocate_and_a_valid_extent_can_render_again() =
    gpuTest { gpu ->
      val gl = gpu.gl.asDynamic()
      ComposeGlJsCompositor(logger = null).use { compositor ->
        CompositedMap(SPLIT_STYLE).use { map ->
          val extent = MapExtent.fromPhysical(FULL, FULL, 1.0)
          val first = assertIs<GlJsFrameTarget.Composited>(compositor.acquire(extent)).target
          map.drawTheWholeStyle(first)
          val limit =
            minOf(
              gl.getParameter(gl.MAX_TEXTURE_SIZE).unsafeCast<Int>(),
              gl.getParameter(gl.MAX_RENDERBUFFER_SIZE).unsafeCast<Int>(),
            )
          assertEquals(0, gl.getError().unsafeCast<Int>(), "the initial frame must leave GL valid")
          assertIs<GlJsFrameTarget.UnsupportedSize>(
            compositor.acquire(MapExtent.fromPhysical(limit + 1, FULL, 1.0))
          )
          assertEquals(
            0,
            gl.getError().unsafeCast<Int>(),
            "oversize must not issue invalid GL calls",
          )
          val recovered = assertIs<GlJsFrameTarget.Composited>(compositor.acquire(extent)).target
          assertTrue(first === recovered, "the usable target should survive a rejected extent")
          assertTrue(map.drawOnce(recovered))
          assertEquals(
            mapOf(RED to FULL * FULL / 2, BLUE to FULL * FULL / 2),
            histogram(readFramebuffer(gl, recovered.framebuffer, FULL, FULL)),
          )
        }
      }
    }

  @Test
  fun map_frames_clear_sampler_objects_left_by_the_shared_renderer() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    browserRenderTarget(FULL, FULL, generation = 1).use { target ->
      CompositedMap(SPLIT_STYLE).use { map ->
        map.drawTheWholeStyle(target)

        val sampler = gl.createSampler()
        check(sampler != null) { "WebGL did not create a sampler object" }
        val textureUnits = gl.getParameter(gl.MAX_TEXTURE_IMAGE_UNITS).unsafeCast<Int>()
        try {
          repeat(textureUnits) { unit -> gl.bindSampler(unit, sampler) }

          assertTrue(map.drawOnce(target), "the map should have drawn with foreign GL state")

          repeat(textureUnits) { unit ->
            gl.activeTexture(gl.TEXTURE0 + unit)
            assertEquals(
              null,
              gl.getParameter(gl.SAMPLER_BINDING),
              "texture unit $unit should not retain the shared renderer's sampler",
            )
          }
        } finally {
          repeat(textureUnits) { unit -> gl.bindSampler(unit, null) }
          gl.activeTexture(gl.TEXTURE0)
          gl.deleteSampler(sampler)
        }
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

        assertTrue(map.drawOnce(second), "a replacement target must render when scheduled")
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
  fun a_throttled_frame_retains_its_overlay_projection_until_the_image_changes() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    val globeStyle =
      BaseStyle.Json(
        SPLIT_STYLE.json.replace(
          "\"version\": 8,",
          "\"version\": 8, \"projection\": {\"type\": \"globe\"},",
        )
      )
    for (style in listOf(SPLIT_STYLE, globeStyle)) {
      browserRenderTarget(FULL, FULL, generation = 1).use { target ->
        CompositedMap(style).use { map ->
          map.drawTheWholeStyle(target)
          val position = Position(0.0, 0.0)
          val original = assertNotNull(map.session.overlayScreenLocationFromPosition(position))
          val pixels = readFramebuffer(gl, target.framebuffer, FULL, FULL)
          map.session.setRenderSettings(RenderOptions { maximumFps = 1 })
          map.session.setCameraPosition(
            CameraPosition(target = Position(20.0, 10.0), zoom = 1.0, bearing = 30.0, tilt = 45.0)
          )
          assertNotEquals(original, map.session.screenLocationFromPosition(position))
          assertFalse(map.drawOnce(target))
          assertEquals(original, map.session.overlayScreenLocationFromPosition(position))
          assertTrue(pixels.contentEquals(readFramebuffer(gl, target.framebuffer, FULL, FULL)))
          map.session.presentFrame(target, MapExtent.fromPhysical(FULL * 2, FULL * 2, 1.0))
          assertEquals(
            DpOffset(original.x * 2, original.y * 2),
            map.session.overlayScreenLocationFromPosition(position),
          )

          map.session.setRenderSettings(RenderOptions {})
          assertTrue(map.drawOnce(target))
          assertEquals(
            map.session.screenLocationFromPosition(position),
            map.session.overlayScreenLocationFromPosition(position),
          )
          assertNotEquals(original, map.session.overlayScreenLocationFromPosition(position))
        }
      }
    }
  }

  @Test
  fun terrain_data_changes_keep_cached_locations_and_defer_new_locations_until_render() =
    gpuTest { gpu ->
      val firstDem = constantDem(1000)
      val nextDem = constantDem(2000)
      val style =
        BaseStyle.Json(
          SPLIT_STYLE.json
            .replace(
              "\"shape\": {",
              "\"dem\": {\"type\": \"raster-dem\", \"tiles\": [\"$firstDem\"], \"tileSize\": 256, \"maxzoom\": 0}, \"shape\": {",
            )
            .replace("\"layers\": [", "\"terrain\": {\"source\": \"dem\"}, \"layers\": [")
        )
      browserRenderTarget(FULL, FULL, generation = 1).use { target ->
        CompositedMap(style).use { map ->
          map.drawOnce(target)
          val engine = assertNotNull(map.session.engineMapForTest())
          val errors = mutableListOf<String>()
          engine.subscribe("error") { errors += it.error?.message ?: "unknown terrain error" }
          map.drawTheWholeStyle(target)
          val position = Position(2.0, 2.0)
          fun elevation(): Double =
            engine.terrain
              .asDynamic()
              .getElevationForLngLat(LngLat(2.0, 2.0), engine._camera.transform)
              .unsafeCast<Double>()
          val loadDeadline = Date.now() + 10_000
          while (abs(elevation() - 1000.0) >= 0.001) {
            check(Date.now() < loadDeadline) { "initial elevation=${elevation()}, errors=$errors" }
            map.drawOnce(target)
            yieldToBrowser()
          }
          map.session.setCameraPosition(
            CameraPosition(target = Position(0.0, 0.0), zoom = 4.0, tilt = 60.0)
          )
          assertTrue(map.drawOnce(target))
          val original = assertNotNull(map.session.overlayScreenLocationFromPosition(position))
          val previousTransform = engine._camera.transform.clone()
          val pixels = readFramebuffer(gpu.gl.asDynamic(), target.framebuffer, FULL, FULL)
          map.session.setRenderSettings(RenderOptions { maximumFps = 1 })
          engine.asDynamic().getSource("dem").setTiles(arrayOf(nextDem))
          val deadline = Date.now() + 10_000
          while (abs(elevation() - 2000.0) >= 0.001) {
            check(Date.now() < deadline) {
              "terrain did not update between map frames: ${elevation()}"
            }
            yieldToBrowser()
          }
          assertEquals(2000.0, elevation(), 0.001)
          assertNotEquals(
            original,
            previousTransform.locationToScreenPoint(LngLat(2.0, 2.0), engine.terrain).toDpOffset(),
            "a retained transform with live terrain would move the overlay",
          )
          assertEquals(original, map.session.overlayScreenLocationFromPosition(position))
          val newlyPlaced = Position(3.0, 3.0)
          assertEquals(null, map.session.overlayScreenLocationFromPosition(newlyPlaced))
          assertTrue(
            pixels.contentEquals(
              readFramebuffer(gpu.gl.asDynamic(), target.framebuffer, FULL, FULL)
            )
          )
          map.session.setRenderSettings(RenderOptions {})
          assertTrue(map.drawOnce(target))
          assertEquals(
            map.session.screenLocationFromPosition(position),
            map.session.overlayScreenLocationFromPosition(position),
          )
          assertEquals(
            map.session.screenLocationFromPosition(newlyPlaced),
            map.session.overlayScreenLocationFromPosition(newlyPlaced),
          )
        }
      }
    }

  @Test
  fun closing_a_composited_map_leaves_the_shared_context_alive() = gpuTest { gpu ->
    val gl = gpu.gl.asDynamic()
    browserRenderTarget(FULL, FULL, generation = 1).use { target ->
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

private fun drawTargetWithSkia(
  skia: org.jetbrains.skia.DirectContext,
  target: GlJsRenderTarget,
): Map<String, Int> {
  val surface =
    Surface.makeRenderTarget(
      skia,
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
      Rect.makeWH(target.widthPx.toFloat(), target.heightPx.toFloat()),
      Rect.makeXYWH(INSET.toFloat(), INSET.toFloat(), SMALL.toFloat(), SMALL.toFloat()),
      SamplingMode.LINEAR,
      null,
      strict = true,
    )
    skia.flush(surface)
    skia.submit(true)

    bitmap.allocPixels(ImageInfo(FULL, FULL, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
    assertTrue(surface.readPixels(bitmap, 0, 0), "the GPU surface should read back")
    return histogram(checkNotNull(bitmap.readPixels()))
  } finally {
    bitmap.close()
    surface.close()
  }
}

private fun constantDem(elevation: Int): String {
  val canvas = document.createElement("canvas").asDynamic()
  canvas.width = 256
  canvas.height = 256
  val context = canvas.getContext("2d")
  val encoded = (elevation + 10_000) * 10
  context.fillStyle = "rgb(${encoded shr 16}, ${(encoded shr 8) and 255}, ${encoded and 255})"
  context.fillRect(0, 0, 256, 256)
  return canvas.toDataURL().unsafeCast<String>()
}
