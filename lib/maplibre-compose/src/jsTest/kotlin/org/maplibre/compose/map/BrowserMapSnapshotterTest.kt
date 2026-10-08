package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
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
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.toImageBitmap
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection
import web.html.HTMLElement

@OptIn(ExperimentalTestApi::class)
class BrowserMapSnapshotterTest {

  @Test
  fun composed_content_renders_in_a_private_target_that_cleanup_removes(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val snapshotter = runtime.createSnapshotter(BackgroundStyle, PointStyle)
      try {
        assertEquals(0, snapshotTargets().size)

        val image =
          snapshotter.capture(
            MapSnapshotRequest(
              size = DpSize(Size.dp, Size.dp),
              cameraPosition =
                CameraPosition(
                  target = Position(longitude = 0.0, latitude = 0.0),
                  zoom = 2.0,
                  padding = DpPadding(left = 24.dp, bottom = 16.dp),
                ),
            )
          )

        assertEquals(Size, image.width)
        assertEquals(Size, image.height)
        assertEquals(Green, image.readPixel(Size - 6, Size / 2 - 8))
        assertEquals(Background, image.readPixel(0, 0))
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
      val content: @Composable @MaplibreComposable () -> Unit = {
        val identity = remember { Any() }
        DisposableEffect(identity) {
          evaluatorIdentities += identity
          onDispose {}
        }
        PointStyle()
      }
      val runtime = createMapRuntime(MapRuntimeOptions())
      val state = runtime.createMapState(baseStyle = BackgroundStyle, content = content)
      val snapshotter = runtime.createSnapshotter(BackgroundStyle, content)
      try {
        setBrowserMapContent(size = Size) { MaplibreMap(state = state) }
        waitUntilMap("the interactive map to become ready") {
          state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
        }
        val presentation = assertNotNull(state.currentMapAttachment)
        val session = presentation.adapter as GlJsMapSession
        val interactiveEngine = assertNotNull(session.engineMapForTest())

        val image =
          snapshotter.capture(
            MapSnapshotRequest(
              size = DpSize(Size.dp, Size.dp),
              cameraPosition =
                CameraPosition(target = Position(longitude = 0.0, latitude = 0.0), zoom = 2.0),
            )
          )

        assertEquals(Green, image.readPixel(Size / 2, Size / 2))
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
  fun consecutive_captures_honor_size_and_density(): Promise<*> = runBrowserMapTest {
    val runtime = createMapRuntime(MapRuntimeOptions())
    val snapshotter = runtime.createSnapshotter(BackgroundStyle)
    try {
      for ((request, size) in
        listOf(
          MapSnapshotRequest(DpSize(32.dp, 24.dp)) to (32 to 24),
          MapSnapshotRequest(DpSize(96.dp, 64.dp), density = Density(2f)) to (192 to 128),
          MapSnapshotRequest(DpSize(1.dp, 1.dp), density = Density(3f)) to (3 to 3),
          MapSnapshotRequest(DpSize(31.dp, 23.dp), density = Density(1.25f)) to (39 to 29),
          MapSnapshotRequest(DpSize(33.dp, 25.dp), density = Density(1.25f)) to (42 to 32),
          MapSnapshotRequest(DpSize(1.dp, 1.dp), density = Density(0.5f)) to (1 to 1),
        )) {
        val captured = snapshotter.capture(request)
        assertEquals(size.first, captured.width, "width for $request")
        assertEquals(size.second, captured.height, "height for $request")
        assertEquals(Background, captured.readPixel(0, 0))
        assertEquals(Background, captured.readPixel(captured.width - 1, captured.height - 1))
      }
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
    val snapshotter = runtime.createSnapshotter(BackgroundStyle, PointStyle)
    try {
      val centered =
        snapshotter.capture(
          MapSnapshotRequest(
            size = DpSize(Size.dp, Size.dp),
            cameraPosition = CameraPosition(zoom = 2.0),
          )
        )
      val shifted =
        snapshotter.capture(
          MapSnapshotRequest(
            size = DpSize(Size.dp, Size.dp),
            cameraPosition =
              CameraPosition(
                target = Position(longitude = 90.0, latitude = 0.0),
                zoom = 2.0,
              ),
          )
        )

      assertEquals(Green, centered.readPixel(Size / 2, Size / 2))
      assertEquals(Background, shifted.readPixel(Size / 2, Size / 2))
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
    val snapshotter = runtime.createSnapshotter(BackgroundStyle)
    try {
      val captured = snapshotter.capture(MapSnapshotRequest(DpSize(31.dp, 23.dp)))
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
      val snapshotter = runtime.createSnapshotter(BackgroundStyle)
      try {
        val error =
          assertFailsWith<IllegalArgumentException> {
            snapshotter.capture(MapSnapshotRequest(DpSize(2_049.dp, 1.dp), density = Density(2f)))
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
    val snapshotter = runtime.createSnapshotter(BackgroundStyle, pointIconStyle(icon))
    val request =
      MapSnapshotRequest(
        size = DpSize(Size.dp, Size.dp),
        cameraPosition = CameraPosition(zoom = 2.0),
      )
    try {
      val first = snapshotter.capture(request)
      val second = snapshotter.capture(request.copy(density = Density(2f)))

      assertEquals(Size, first.width)
      assertEquals(Size, first.height)
      assertEquals(Size * 2, second.width)
      assertEquals(Size * 2, second.height)
      assertEquals(Green, first.readPixel(Size / 2, Size / 2))
      assertEquals(Background, first.readPixel(Size / 2 + 6, Size / 2))
      assertEquals(Green, second.readPixel(Size, Size))
      assertEquals(Background, second.readPixel(Size + 12, Size))
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
    val snapshotter = runtime.createSnapshotter(EmptyStyle)
    try {
      val opaque = snapshotter.capture(MapSnapshotRequest(DpSize(8.dp, 8.dp)))
      val transparent =
        snapshotter.capture(
          MapSnapshotRequest(
            size = DpSize(8.dp, 8.dp),
            transparent = true,
          )
        )

      assertEquals(White, opaque.readPixel(0, 0))
      assertEquals(Transparent, transparent.readPixel(0, 0))
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
        if (url == BlockedStyleUri) {
          styleRequested = true
          Promise<dynamic> { _, _ -> }
        } else {
          originalFetch.call(global, input, init)
        }
      }
      val runtime = createMapRuntime(MapRuntimeOptions())
      val snapshotter = runtime.createSnapshotter(BaseStyle.Uri(BlockedStyleUri), PointStyle)
      try {
        coroutineScope {
          val capture = async { snapshotter.capture(MapSnapshotRequest(DpSize(Size.dp, Size.dp))) }
          waitUntilMap("the snapshot style request to start") {
            styleRequested && snapshotTargets().size == 1
          }

          capture.cancelAndJoin()

          waitUntilMap("capture cancellation to release the private engine") {
            snapshotTargets().isEmpty() && snapshotter.style.loadState == StyleLoadState.Pending
          }
        }
        snapshotter.style.asMutable!!.baseStyle = BackgroundStyle
        val image =
          snapshotter.capture(
            MapSnapshotRequest(
              size = DpSize(Size.dp, Size.dp),
              cameraPosition = CameraPosition(zoom = 2.0),
            )
          )
        assertEquals(Green, image.readPixel(Size / 2, Size / 2))
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

  private fun pointIconStyle(icon: ImageBitmap): @Composable @MaplibreComposable () -> Unit = {
    val points =
      GeoJsonSource(
        id = "icon-points",
        data =
          GeoJsonData.Features(
            buildFeatureCollection<Geometry, JsonObject?> {
              addFeature(geometry = Point(Position(longitude = 0.0, latitude = 0.0)))
            }
          ),
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
    const val Size = 64
    const val BlockedStyleUri = "https://snapshot-style.test/style.json"
    val Background = RgbaPixel(red = 51, green = 102, blue = 153, alpha = 255)
    val Green = RgbaPixel(red = 0, green = 255, blue = 0, alpha = 255)
    val White = RgbaPixel(red = 255, green = 255, blue = 255, alpha = 255)
    val Transparent = RgbaPixel(red = 0, green = 0, blue = 0, alpha = 0)
    val EmptyStyle = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val BackgroundStyle =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"base-background","type":"background","paint":{"background-color":"#336699"}}
        ]}
        """
          .trimIndent()
      )
    val PointStyle: @Composable @MaplibreComposable () -> Unit = {
      val points =
        GeoJsonSource(
          id = "points",
          data =
            GeoJsonData.Features(
              buildFeatureCollection<Geometry, JsonObject?> {
                addFeature(geometry = Point(Position(longitude = 0.0, latitude = 0.0)))
              }
            ),
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
