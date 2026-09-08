package org.maplibre.compose.gljs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.map.GlJsMapSession
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class BrowserMapLifecycleTest {

  private fun installDeferredReplayStyle(): DeferredReplayStyle {
    val global = js("window")
    val original = global.fetch
    var resolveStyle: ((dynamic) -> Unit)? = null
    var resolveSource: ((dynamic) -> Unit)? = null
    global.fetch = { input: dynamic, init: dynamic ->
      val url = if (jsTypeOf(input) == "string") input as String else input.url as String
      when {
        url.endsWith("/style.json") -> Promise<dynamic> { resolve, _ -> resolveStyle = resolve }
        url.endsWith("/source.json") -> Promise<dynamic> { resolve, _ -> resolveSource = resolve }
        else -> original.call(global, input, init)
      }
    }
    return DeferredReplayStyle(
      restore = { global.fetch = original },
      isStyleRequested = { resolveStyle != null },
      resolveStyle = {
        val body = REPLAY_STYLE_JSON
        val response = js("new Response(body, { headers: { 'content-type': 'application/json' } })")
        checkNotNull(resolveStyle).invoke(response)
      },
      isSourceRequested = { resolveSource != null },
      resolveSource = {
        val body = REPLAY_SOURCE_JSON
        val response = js("new Response(body, { headers: { 'content-type': 'application/json' } })")
        checkNotNull(resolveSource).invoke(response)
      },
    )
  }

  private class DeferredReplayStyle(
    val restore: () -> Unit,
    val isStyleRequested: () -> Boolean,
    val resolveStyle: () -> Unit,
    val isSourceRequested: () -> Boolean,
    val resolveSource: () -> Unit,
  )

  @Test
  fun a_web_map_is_destroyed_and_recreated_with_its_durable_camera(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val initialCamera =
        CameraPosition(target = Position(longitude = 11.0, latitude = 47.0), zoom = 8.0)
      val replayedCamera =
        CameraPosition(
          bearing = 20.0,
          target = Position(longitude = -122.4, latitude = 37.8),
          tilt = 30.0,
          zoom = 10.0,
        )
      val state =
        runtime.createMapState(
          cameraPosition = initialCamera,
          baseStyle = STYLE_A,
        )
      val presented = mutableStateOf(true)

      setBrowserMapContent { if (presented.value) MaplibreMap(state = state) }
      waitUntilMap("the first Web presentation to become ready") {
        state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
      }
      val firstPresentation = requireNotNull(state.currentMapAttachment)
      val firstSession = firstPresentation.adapter as GlJsMapSession
      val firstEngine = requireNotNull(firstSession.engineMapForTest())

      state.setCameraPosition(replayedCamera)
      waitUntilMap("the logical map to accept the camera") {
        state.cameraPosition.isNear(replayedCamera)
      }

      runOnIdle { presented.value = false }
      waitUntilMap("the departed GL JS map to be destroyed") {
        state.currentMapAttachment == null && firstSession.engineMapForTest() == null
      }

      assertTrue(!firstPresentation.isValid)
      assertNull(firstSession.engineMapForTest())
      assertEquals(StyleLoadState.Pending, state.style.loadState)

      val deferredStyle = installDeferredReplayStyle()
      try {
        state.style.asMutable!!.baseStyle = REPLAY_STYLE
        runOnIdle { presented.value = true }
        waitUntilMap("the replacement Web map to request its retained style") {
          state.currentMapAttachment != null && deferredStyle.isStyleRequested()
        }
        val replacementSession =
          requireNotNull(state.currentMapAttachment).adapter as GlJsMapSession

        assertNotSame(firstSession, replacementSession)
        assertNotSame(firstEngine, replacementSession.engineMapForTest())
        assertEquals(StyleLoadState.Loading, state.style.loadState)
        assertTrue(replacementSession.getCameraPosition().isNear(replayedCamera))
        assertTrue(!replacementSession.canPresentFrames)

        deferredStyle.resolveStyle()
        waitUntilMap("the replacement style to request its source metadata") {
          deferredStyle.isSourceRequested()
        }

        assertEquals(StyleLoadState.Loading, state.style.loadState)
        assertTrue(!replacementSession.canPresentFrames)

        deferredStyle.resolveSource()
        waitUntilMap("the replacement Web presentation to replay the logical map") {
          state.currentMapAttachment != null &&
            state.style.loadState == StyleLoadState.Ready &&
            state.cameraPosition.isNear(replayedCamera)
        }

        assertTrue(state.cameraPosition.isNear(replayedCamera))
        assertTrue(replacementSession.canPresentFrames)
        assertEquals(
          listOf("replayed"),
          requireNotNull(replacementSession.engineMapForTest()).getStyle().layers.map { it.id },
        )
      } finally {
        deferredStyle.restore()
      }

      runtime.close()
      runtime.awaitClosed()
    }

  @Test
  fun pixel_density_renews_the_presentation_and_font_scale_preserves_it(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val density = mutableStateOf(Density(1f))
      var observedDensity: Density? = null
      val camera = CameraPosition(target = Position(11.0, 47.0), zoom = 8.0)
      val state =
        runtime.createMapState(BaseStyle.Empty, cameraPosition = camera) {
          val current = LocalDensity.current
          SideEffect { observedDensity = current }
          key(current.density, current.fontScale) {
            BackgroundLayer(
              id =
                when {
                  current.fontScale > 1f -> "scaled-font"
                  current.density > 1f -> "scaled-density"
                  else -> "initial-density"
                },
              color = const(Color.Red),
            )
          }
        }
      try {
        setBrowserMapContent {
          CompositionLocalProvider(LocalDensity provides density.value) {
            MaplibreMap(state = state)
          }
        }
        waitUntilMap("the first density's declarative style") {
          state.currentMapAttachment != null &&
            state.style.loadState == StyleLoadState.Ready &&
            (state.currentMapAttachment?.adapter as? GlJsMapSession)?.canPresentFrames == true
        }
        val firstAttachment = requireNotNull(state.currentMapAttachment)
        val firstSession = firstAttachment.adapter as GlJsMapSession
        val firstEngine = requireNotNull(firstSession.engineMapForTest())
        runOnIdle { density.value = Density(2f) }
        waitUntilMap("a replacement presentation after the browser density changes") {
          val current = state.currentMapAttachment
          current != null &&
            current !== firstAttachment &&
            state.style.loadState == StyleLoadState.Ready &&
            (current.adapter as GlJsMapSession).canPresentFrames
        }
        val replacement = requireNotNull(state.currentMapAttachment)
        val replacementSession = replacement.adapter as GlJsMapSession
        val replacementEngine = requireNotNull(replacementSession.engineMapForTest())
        assertFalse(firstAttachment.isValid)
        assertNull(firstSession.engineMapForTest())
        assertNotSame(firstSession, replacementSession)
        assertNotSame(firstEngine, replacementEngine)
        assertTrue(state.cameraPosition.isNear(camera))
        waitUntilMap("the replacement engine's density-dependent style") {
          replacementEngine.getStyle().layers.any { it.id == "scaled-density" }
        }
        runOnIdle { density.value = Density(2f, fontScale = 1.5f) }
        waitUntilMap("font scale to recompose without replacing the browser engine") {
          observedDensity?.fontScale == 1.5f &&
            replacementEngine.getStyle().layers.any { it.id == "scaled-font" } &&
            replacementEngine.getStyle().layers.none { it.id == "scaled-density" }
        }
        assertSame(replacement, state.currentMapAttachment)
        assertSame(replacementEngine, replacementSession.engineMapForTest())
        assertTrue(state.cameraPosition.isNear(camera))
      } finally {
        runtime.close()
        runtime.awaitClosed()
      }
    }

  private companion object {
    val STYLE_A =
      BaseStyle.Json(
        """{"version":8,"name":"a","sources":{},"layers":[{"id":"a","type":"background"}]}"""
      )
    val REPLAY_STYLE = BaseStyle.Uri("https://replay-style.test/style.json")
    const val REPLAY_STYLE_JSON =
      """{"version":8,"sources":{"replay-source":{"type":"vector","url":"https://replay-style.test/source.json"}},"layers":[{"id":"replayed","type":"background"}]}"""
    const val REPLAY_SOURCE_JSON =
      """{"tilejson":"3.0.0","tiles":["https://example.invalid/{z}/{x}/{y}.pbf"]}"""
  }
}
