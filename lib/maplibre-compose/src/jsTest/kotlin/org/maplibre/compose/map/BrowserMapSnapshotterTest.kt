package org.maplibre.compose.map

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.js.Promise
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.browser.document
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleComposition
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.util.toImageBitmap
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection
import web.html.HTMLCanvasElement
import web.html.HTMLElement

@OptIn(ExperimentalTestApi::class)
class BrowserMapSnapshotterTest {

  @Test
  fun composed_content_renders_in_a_private_target_that_cleanup_removes(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val snapshotter = runtime.createSnapshotter(BASE_STYLE, POINT_STYLE)
      try {
        assertEquals(0, snapshotTargets().size)

        val image =
          snapshotter.capture(
            MapSnapshotRequest(
              width = SIZE,
              height = SIZE,
              cameraPosition =
                CameraPosition(target = Position(longitude = 0.0, latitude = 0.0), zoom = 2.0),
            )
          )

        assertEquals(SIZE, image.width)
        assertEquals(SIZE, image.height)
        assertEquals(GREEN, image.readPixel(SIZE / 2, SIZE / 2))
        assertEquals(BACKGROUND, image.readPixel(0, 0))
        val target = assertNotNull(snapshotTargets().singleOrNull())
        assertTrue(target.style.visibility == "hidden")
        assertTrue(target.parentElement === document.body)
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
        assertEquals(0, snapshotTargets().size)
        runtime.close()
        runtime.awaitClosed()
      }
    }

  @Test
  fun capture_keeps_an_interactive_map_attached_and_evaluates_style_independently(): Promise<*> =
    runBrowserMapTest {
      val evaluatorIdentities = mutableSetOf<Any>()
      val composition = StyleComposition {
        val identity = remember { Any() }
        DisposableEffect(identity) {
          evaluatorIdentities += identity
          onDispose {}
        }
        POINT_STYLE.content()
      }
      val runtime = createMapRuntime(MapRuntimeOptions())
      val state = runtime.createMapState(baseStyle = BASE_STYLE, styleComposition = composition)
      val snapshotter = runtime.createSnapshotter(BASE_STYLE, composition)
      try {
        setBrowserMapContent(size = SIZE) { MaplibreMap(state = state) }
        waitUntilMap("the interactive map to become ready") {
          state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
        }
        val presentation = assertNotNull(state.currentMapAttachment)
        val session = presentation.adapter as GlJsMapSession
        val interactiveEngine = assertNotNull(session.engineMapForTest())

        val image =
          snapshotter.capture(
            MapSnapshotRequest(
              width = SIZE,
              height = SIZE,
              cameraPosition =
                CameraPosition(target = Position(longitude = 0.0, latitude = 0.0), zoom = 2.0),
            )
          )

        assertEquals(GREEN, image.readPixel(SIZE / 2, SIZE / 2))
        assertEquals(2, evaluatorIdentities.size)
        assertSame(presentation, state.currentMapAttachment)
        assertSame(interactiveEngine, session.engineMapForTest())
        snapshotter.close()
        snapshotter.awaitClosed()
        assertSame(presentation, state.currentMapAttachment)
        assertSame(interactiveEngine, session.engineMapForTest())
        assertEquals(StyleLoadState.Ready, state.style.loadState)
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
        state.close()
        state.awaitClosed()
        runtime.close()
        runtime.awaitClosed()
      }
    }

  @Test
  fun consecutive_requests_reuse_the_private_map_at_each_requested_extent(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val snapshotter = runtime.createSnapshotter(BASE_STYLE, POINT_STYLE)
      try {
        val first = snapshotter.capture(MapSnapshotRequest(width = 32, height = 24))
        val target = assertNotNull(snapshotTargets().singleOrNull())
        val canvas = assertNotNull(target.querySelector("canvas")).unsafeCast<HTMLCanvasElement>()

        assertEquals(32, first.width)
        assertEquals(24, first.height)
        assertEquals(32, canvas.width)
        assertEquals(24, canvas.height)

        val second = snapshotter.capture(MapSnapshotRequest(width = 96, height = 64, density = 2f))

        assertSame(target, snapshotTargets().singleOrNull())
        assertEquals(192, second.width)
        assertEquals(128, second.height)
        assertEquals(192, canvas.width)
        assertEquals(128, canvas.height)
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
        runtime.close()
        runtime.awaitClosed()
      }
    }

  @Test
  fun camera_position_is_a_per_capture_value(): Promise<*> = runBrowserMapTest {
    val runtime = createMapRuntime(MapRuntimeOptions())
    val snapshotter = runtime.createSnapshotter(BASE_STYLE, POINT_STYLE)
    try {
      val centered =
        snapshotter.capture(
          MapSnapshotRequest(
            width = SIZE,
            height = SIZE,
            cameraPosition = CameraPosition(zoom = 2.0),
          )
        )
      val shifted =
        snapshotter.capture(
          MapSnapshotRequest(
            width = SIZE,
            height = SIZE,
            cameraPosition =
              CameraPosition(
                target = Position(longitude = 90.0, latitude = 0.0),
                zoom = 2.0,
              ),
          )
        )

      assertEquals(GREEN, centered.readPixel(SIZE / 2, SIZE / 2))
      assertEquals(BACKGROUND, shifted.readPixel(SIZE / 2, SIZE / 2))
    } finally {
      snapshotter.close()
      snapshotter.awaitClosed()
      runtime.close()
      runtime.awaitClosed()
    }
  }

  @Test
  fun density_scales_the_bitmap_without_changing_the_logical_viewport(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val snapshotter = runtime.createSnapshotter(BASE_STYLE, StyleComposition {})
      try {
        val captured = snapshotter.capture(MapSnapshotRequest(width = 1, height = 1, density = 3f))
        val target = assertNotNull(snapshotTargets().singleOrNull())
        val canvas = assertNotNull(target.querySelector("canvas")).unsafeCast<HTMLCanvasElement>()

        assertEquals(3, captured.width)
        assertEquals(3, captured.height)
        assertEquals(1, target.clientWidth)
        assertEquals(1, target.clientHeight)
        assertEquals(3, canvas.width)
        assertEquals(3, canvas.height)
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
        runtime.close()
        runtime.awaitClosed()
      }
    }

  @Test
  fun fractional_density_rounds_the_output_up_from_the_gl_js_canvas(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val snapshotter = runtime.createSnapshotter(BASE_STYLE, StyleComposition {})
      try {
        val captured =
          snapshotter.capture(MapSnapshotRequest(width = 31, height = 23, density = 1.25f))
        val target = assertNotNull(snapshotTargets().singleOrNull())
        val canvas = assertNotNull(target.querySelector("canvas")).unsafeCast<HTMLCanvasElement>()

        assertEquals(39, captured.width)
        assertEquals(29, captured.height)
        assertEquals(31, target.clientWidth)
        assertEquals(23, target.clientHeight)
        assertEquals(38, canvas.width)
        assertEquals(28, canvas.height)
        assertEquals(BACKGROUND, captured.readPixel(38, 28))
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
        runtime.close()
        runtime.awaitClosed()
      }
    }

  @Test
  fun subpixel_density_keeps_the_gl_js_canvas_nonzero(): Promise<*> = runBrowserMapTest {
    val runtime = createMapRuntime(MapRuntimeOptions())
    val snapshotter = runtime.createSnapshotter(BASE_STYLE, StyleComposition {})
    try {
      val captured = snapshotter.capture(MapSnapshotRequest(width = 1, height = 1, density = 0.5f))
      val target = assertNotNull(snapshotTargets().singleOrNull())
      val canvas = assertNotNull(target.querySelector("canvas")).unsafeCast<HTMLCanvasElement>()

      assertEquals(1, captured.width)
      assertEquals(1, captured.height)
      assertEquals(1, target.clientWidth)
      assertEquals(1, target.clientHeight)
      assertEquals(1, canvas.width)
      assertEquals(1, canvas.height)
    } finally {
      snapshotter.close()
      snapshotter.awaitClosed()
      runtime.close()
      runtime.awaitClosed()
    }
  }

  @Test
  fun page_css_does_not_change_the_private_viewport(): Promise<*> = runBrowserMapTest {
    val pageStyle = document.createElement("style").unsafeCast<HTMLElement>()
    pageStyle.textContent =
      "[data-maplibre-compose-snapshotter] { " +
        "box-sizing: border-box; border: 7px solid; padding: 11px; }"
    document.body?.appendChild(pageStyle.asDynamic())
    val runtime = createMapRuntime(MapRuntimeOptions())
    val snapshotter = runtime.createSnapshotter(BASE_STYLE, StyleComposition {})
    try {
      val captured = snapshotter.capture(MapSnapshotRequest(width = 31, height = 23))
      val target = assertNotNull(snapshotTargets().singleOrNull())

      assertEquals(31, captured.width)
      assertEquals(23, captured.height)
      assertEquals(31, target.clientWidth)
      assertEquals(23, target.clientHeight)
    } finally {
      snapshotter.close()
      snapshotter.awaitClosed()
      runtime.close()
      runtime.awaitClosed()
      pageStyle.remove()
    }
  }

  @Test
  fun a_request_above_the_web_canvas_limit_fails_before_map_creation(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val snapshotter = runtime.createSnapshotter(BASE_STYLE, StyleComposition {})
      try {
        val error =
          assertFailsWith<IllegalArgumentException> {
            snapshotter.capture(MapSnapshotRequest(width = 2_049, height = 1, density = 2f))
          }

        assertTrue(error.message.orEmpty().contains("4096px canvas limit"))
        assertEquals(0, snapshotTargets().size)
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
        runtime.close()
        runtime.awaitClosed()
      }
    }

  @Test
  fun a_density_change_reapplies_an_unchanged_style_image(): Promise<*> = runBrowserMapTest {
    val icon = IntArray(8 * 8) { 0xff00ff00.toInt() }.toImageBitmap(8, 8)
    val runtime = createMapRuntime(MapRuntimeOptions())
    val snapshotter = runtime.createSnapshotter(BASE_STYLE, pointIconStyle(icon))
    val request =
      MapSnapshotRequest(
        width = SIZE,
        height = SIZE,
        cameraPosition = CameraPosition(zoom = 2.0),
      )
    try {
      val first = snapshotter.capture(request)
      val second = snapshotter.capture(request.copy(density = 2f))

      assertEquals(SIZE, first.width)
      assertEquals(SIZE, first.height)
      assertEquals(SIZE * 2, second.width)
      assertEquals(SIZE * 2, second.height)
      assertEquals(GREEN, first.readPixel(SIZE / 2, SIZE / 2))
      assertEquals(BACKGROUND, first.readPixel(SIZE / 2 + 6, SIZE / 2))
      assertEquals(GREEN, second.readPixel(SIZE, SIZE))
      assertEquals(BACKGROUND, second.readPixel(SIZE + 12, SIZE))
    } finally {
      snapshotter.close()
      snapshotter.awaitClosed()
      runtime.close()
      runtime.awaitClosed()
    }
  }

  @Test
  fun output_transparency_is_a_per_capture_value(): Promise<*> = runBrowserMapTest {
    val runtime = createMapRuntime(MapRuntimeOptions())
    val snapshotter = runtime.createSnapshotter(EMPTY_STYLE, StyleComposition {})
    try {
      val opaque = snapshotter.capture(MapSnapshotRequest(width = 8, height = 8))
      val transparent =
        snapshotter.capture(
          MapSnapshotRequest(
            width = 8,
            height = 8,
            outputOptions = MapSnapshotOutputOptions(transparent = true),
          )
        )

      assertEquals(WHITE, opaque.readPixel(0, 0))
      assertEquals(TRANSPARENT, transparent.readPixel(0, 0))
    } finally {
      snapshotter.close()
      snapshotter.awaitClosed()
      runtime.close()
      runtime.awaitClosed()
    }
  }

  @Test
  fun cancelling_an_active_capture_releases_and_recreates_its_private_engine(): Promise<*> =
    runBrowserMapTest {
      val global = js("window")
      val originalFetch = global.fetch
      var styleRequested = false
      global.fetch = { input: dynamic, init: dynamic ->
        val url = if (jsTypeOf(input) == "string") input as String else input.url as String
        if (url == BLOCKED_STYLE_URI) {
          styleRequested = true
          Promise<dynamic> { _, _ -> }
        } else {
          originalFetch.call(global, input, init)
        }
      }
      val runtime = createMapRuntime(MapRuntimeOptions())
      val snapshotter = runtime.createSnapshotter(BaseStyle.Uri(BLOCKED_STYLE_URI), POINT_STYLE)
      try {
        coroutineScope {
          val capture = async { snapshotter.capture(MapSnapshotRequest(SIZE, SIZE)) }
          waitUntilMap("the snapshot style request to start") {
            styleRequested && snapshotTargets().size == 1
          }

          capture.cancelAndJoin()

          waitUntilMap("capture cancellation to release the private engine") {
            snapshotTargets().isEmpty() && snapshotter.style.loadState == StyleLoadState.Pending
          }
        }
        snapshotter.style.baseStyle = BASE_STYLE
        val image =
          snapshotter.capture(
            MapSnapshotRequest(
              width = SIZE,
              height = SIZE,
              cameraPosition = CameraPosition(zoom = 2.0),
            )
          )
        assertEquals(GREEN, image.readPixel(SIZE / 2, SIZE / 2))
        assertEquals(1, snapshotTargets().size)
      } finally {
        global.fetch = originalFetch
        snapshotter.close()
        snapshotter.awaitClosed()
        runtime.close()
        runtime.awaitClosed()
      }
    }

  private fun snapshotTargets(): List<HTMLElement> {
    val targets = document.querySelectorAll("[data-maplibre-compose-snapshotter]")
    return List(targets.length) { index -> targets.item(index).unsafeCast<HTMLElement>() }
  }

  private fun pointIconStyle(icon: ImageBitmap) = StyleComposition {
    val points =
      GeoJsonSource(
        id = "icon-points",
        data =
          GeoJsonData.Features(
            buildFeatureCollection<Geometry, JsonObject?> {
              addFeature(geometry = Point(Position(longitude = 0.0, latitude = 0.0)))
            }
          ),
        options = GeoJsonOptions(),
      )
    SymbolLayer(
      id = "composed-icon",
      source = points,
      iconImage = image(icon),
      iconAllowOverlap = const(true),
      iconIgnorePlacement = const(true),
    )
  }

  private fun ImageBitmap.readPixel(x: Int, y: Int): RgbaPixel {
    val pixel = IntArray(1)
    readPixels(pixel, startX = x, startY = y, width = 1, height = 1)
    return pixel.single().toRgbaPixel()
  }

  private fun Int.toRgbaPixel() =
    RgbaPixel(
      red = this ushr 16 and 0xff,
      green = this ushr 8 and 0xff,
      blue = this and 0xff,
      alpha = this ushr 24 and 0xff,
    )

  private companion object {
    const val SIZE = 64
    const val BLOCKED_STYLE_URI = "https://snapshot-style.test/style.json"
    val BACKGROUND = RgbaPixel(red = 51, green = 102, blue = 153, alpha = 255)
    val GREEN = RgbaPixel(red = 0, green = 255, blue = 0, alpha = 255)
    val WHITE = RgbaPixel(red = 255, green = 255, blue = 255, alpha = 255)
    val TRANSPARENT = RgbaPixel(red = 0, green = 0, blue = 0, alpha = 0)
    val EMPTY_STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val BASE_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"base-background","type":"background","paint":{"background-color":"#336699"}}
        ]}
        """
          .trimIndent()
      )
    val POINT_STYLE = StyleComposition {
      val points =
        GeoJsonSource(
          id = "points",
          data =
            GeoJsonData.Features(
              buildFeatureCollection<Geometry, JsonObject?> {
                addFeature(geometry = Point(Position(longitude = 0.0, latitude = 0.0)))
              }
            ),
          options = GeoJsonOptions(),
        )
      CircleLayer(
        id = "composed-circle",
        source = points,
        color = const(Color.Green),
        radius = const(20.dp),
      )
    }
  }
}
