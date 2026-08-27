@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setMultiUseFfiTestMapContent
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * A camera animation started from a scope that outlives the [MaplibreMap] composable: the detach
 * pauses it, and a re-attached session resumes and completes it.
 */
@OptIn(ExperimentalTestApi::class)
class MlnFfiCameraDetachTest {

  private val cache = FfiTestCache()

  @AfterTest
  fun cleanUp() {
    cache.close()
  }

  @Test
  fun a_detach_pauses_a_camera_animation_until_a_session_reattaches() = runFfiComposeUiTest {
    cache.configure()
    val frames = AtomicInt(0)
    var loadsFinished = 0
    var attached by mutableStateOf(true)
    lateinit var state: MapState

    setMultiUseFfiTestMapContent {
      val mapState = rememberMapState(baseStyle = STYLE)
      state = mapState
      if (attached) {
        Box(Modifier.fillMaxSize()) {
          MaplibreMap(
            state = mapState,
            modifier = Modifier.fillMaxSize(),
            onFrame = { frames.incrementAndFetch() },
            onMapLoadFinished = { loadsFinished++ },
          )
        }
      }
    }

    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { loadsFinished > 0 && frames.load() > 0 }

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
      assertFalse(state.isCameraMoving, "a detached map must not report itself moving")
      assertEquals(CameraMoveReason.NONE, state.cameraMoveReason, "the move reason must reset")
      assertFalse(animation.isCompleted, "the detach must keep the animation call suspended")

      runOnUiThread { attached = true }
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { state.isAttached }
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { animation.isCompleted }
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { !state.isCameraMoving }
    } finally {
      animationScope.cancel()
    }
  }

  private companion object {
    const val SETTLE_TIMEOUT_MILLIS = 30_000L

    val STYLE =
      BaseStyle.Json("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background"}]}""")
  }
}
