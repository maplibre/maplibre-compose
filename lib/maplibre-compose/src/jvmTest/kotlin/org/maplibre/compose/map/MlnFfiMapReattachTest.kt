@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setMultiUseFfiTestMapContent
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/**
 * The session leaves and re-enters the composition against the same [MapState], twice: the first
 * cycle shows the core, its loaded style, and the camera surviving the detach; the second shows a
 * detach ending a running camera animation at the position that it reached.
 */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapReattachTest {

  private val cache = FfiTestCache()

  @AfterTest
  fun cleanUp() {
    cache.close()
  }

  @Test
  fun a_detach_reattach_cycle_keeps_the_core_style_and_camera_and_ends_a_running_animation() =
    runFfiComposeUiTest {
      cache.configure()
      val frames = AtomicInt(0)
      val errors = mutableListOf<String>()
      var loadsFinished = 0
      var attached by mutableStateOf(true)
      val firstPosition =
        CameraPosition(target = Position(longitude = 11.0, latitude = 47.0), zoom = 5.0)
      lateinit var state: MapState

      setMultiUseFfiTestMapContent {
        val mapState =
          rememberMapState(initialCameraPosition = firstPosition, baseStyle = STYLE) {
            FillLayer(
              id = "user-fill",
              source =
                rememberGeoJsonSource(
                  data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>())
                ),
              color = const(Color.Red),
            )
          }
        state = mapState
        if (attached) {
          Box(Modifier.fillMaxSize()) {
            MaplibreMap(
              state = mapState,
              modifier = Modifier.fillMaxSize(),
              logger = remember { Logger.withTag("reattach-test") },
              onFrame = { frames.incrementAndFetch() },
              onMapLoadFailed = { errors += "mapLoadFailed: $it" },
              onMapLoadFinished = { loadsFinished++ },
            )
          }
        }
      }

      // Step 1: the first attach loads the style and renders.
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { loadsFinished > 0 && frames.load() > 0 }
      val engine = state.engine
      val core = requireNotNull(engine.core) { "no core after the first attach" }
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { "user-fill" in state.liveStyleLayerIds() }

      // Step 2: the session detaches; the engine retains the live core.
      runOnUiThread { attached = false }
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { !state.isAttached }
      assertSame(core, engine.core, "the core must survive the session detach")
      val loadsBeforeReattach = loadsFinished
      val framesBeforeReattach = frames.load()

      // Step 3: a re-attach reuses the core, its loaded style, and the recorded camera.
      runOnUiThread { attached = true }
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
        state.isAttached && frames.load() > framesBeforeReattach
      }

      assertSame(core, engine.core, "a re-attach must reuse the live core")
      assertSame(core, state.attachedAdapter, "the camera must rewire to the same core")
      assertEquals(loadsBeforeReattach, loadsFinished, "a re-attach must not reload the style")
      assertTrue(
        "user-fill" in state.liveStyleLayerIds(),
        "the composed layer must still be in the loaded style",
      )
      val camera = core.getCameraPosition()
      assertEquals(firstPosition.target.longitude, camera.target.longitude, 1e-4, "longitude")
      assertEquals(firstPosition.target.latitude, camera.target.latitude, 1e-4, "latitude")
      assertEquals(firstPosition.zoom, camera.zoom, 1e-4, "zoom")

      // Step 4: a running animation ends with its session; the call returns where it reached.
      val animationScope = CoroutineScope(Dispatchers.Default)
      try {
        val animation = animationScope.launch {
          state.animateCamera(
            CameraPosition(target = Position(longitude = 30.0, latitude = 30.0), zoom = 6.0),
            duration = 15.seconds,
          )
        }
        waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { state.isCameraMoving }

        runOnUiThread { attached = false }
        waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { !state.isAttached }
        waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { animation.isCompleted }
        assertFalse(state.isCameraMoving, "a detached map must not report itself moving")
        assertEquals(CameraMoveReason.NONE, state.cameraMoveReason, "the move reason must reset")
        val ended = core.getCameraPosition()
        assertTrue(
          ended.target.longitude < 30.0 - 1e-4,
          "the ended animation must stop short of its destination, was ${ended.target.longitude}",
        )

        // Step 5: the next re-attach keeps the ended animation's camera; nothing resumes.
        val framesBeforeFinalAttach = frames.load()
        runOnUiThread { attached = true }
        waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { state.isAttached }
        waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { frames.load() > framesBeforeFinalAttach }
        assertFalse(state.isCameraMoving, "no animation may resume with the new session")
        val settled = core.getCameraPosition()
        assertEquals(ended.target.longitude, settled.target.longitude, 1e-4, "frozen longitude")
        assertEquals(ended.target.latitude, settled.target.latitude, 1e-4, "frozen latitude")
        assertEquals(0, core.transitionWaiterCountForTest(), "no waiter may survive the detach")
      } finally {
        animationScope.cancel()
      }
      assertTrue(errors.isEmpty(), "the cycles reported errors: $errors")
    }

  private companion object {
    const val SETTLE_TIMEOUT_MILLIS = 30_000L

    val STYLE =
      BaseStyle.Json("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background"}]}""")
  }
}
