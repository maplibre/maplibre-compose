package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions

/**
 * A ViewModel-style [MapState]: constructed, composed, and closed with no session ever attached.
 */
class BareMapStateTest {

  @Test
  fun a_bare_state_reports_empty_collections_composes_content_and_closes() = runTest {
    val hostDispatcher = recordingHostDispatcher()
    val state = MapState(cameraPosition = CameraPosition(), hostDispatcher = hostDispatcher)

    // With no style loaded, the style collections are empty rather than failing.
    assertTrue(state.layers.ids.isEmpty(), "no layer ids before a style loads")
    assertTrue(state.sources.ids.isEmpty(), "no source ids before a style loads")
    assertNull(state.layers["any"], "no layer handle before a style loads")
    assertNull(state.sources["any"], "no source before a style loads")

    val composed = CompletableDeferred<Unit>()
    val source =
      RasterSource("tiles", listOf("https://example.invalid/{z}/{x}/{y}.png"), TileSetOptions())

    state.setStyleContent {
      RasterLayer(id = "raster", source = source)
      composed.complete(Unit)
    }

    // The composition runs on the state's own dispatcher, so the wait leaves the test dispatcher.
    withContext(Dispatchers.Default) { withTimeout(30.seconds) { composed.await() } }
    assertFalse(state.isAttached, "no session ever attached")

    state.close()
    // The host releases its dispatcher last, after it has disposed the content.
    withContext(Dispatchers.Default) {
      withTimeout(30.seconds) { hostDispatcher.closedState.first { it } }
    }
  }
}
