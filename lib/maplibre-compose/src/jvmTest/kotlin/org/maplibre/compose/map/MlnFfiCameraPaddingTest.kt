package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.spatialk.geojson.Position

/**
 * A camera-padding change lands as a jump, and a jump cancels transitions, so a change arriving
 * mid-animation must defer rather than silently end the animation — the path Android's late window
 * insets drive on every attach-time animation.
 */
class MlnFfiCameraPaddingTest {

  private val cache = FfiTestCache()
  private var state: MapState? = null

  @AfterTest
  fun cleanUp() {
    state?.close()
    MlnFfiApplication.resetForTest()
    cache.close()
  }

  @Test
  fun a_padding_change_does_not_cancel_a_running_camera_animation() {
    cache.configure()
    val state = MapState().also { state = it }
    val core = state.engine.acquireCore(1.0, LayoutDirection.Ltr, MapRenderBackend.VULKAN)
    core.start()
    state.attachSession(core)
    runBlocking {
      val move =
        async(Dispatchers.Default) {
          state.animateCamera(
            CameraPosition(target = Position(longitude = 10.0, latitude = 10.0), zoom = 5.0),
            duration = 60.seconds,
          )
        }
      while (core.transitionWaiterCountForTest() == 0) delay(10)

      core.setCameraPadding(PaddingValues(16.dp))
      // The barrier runs after the owner thread has handled the padding change and its events.
      val drained = CompletableDeferred<Unit>()
      core.postEventDrainBarrierForTest { drained.complete(Unit) }
      drained.await()
      // A cancelled transition resumes its waiter on another dispatcher, so give it time to land.
      delay(200)

      assertFalse(move.isCompleted, "the padding change ended the animation")
      assertEquals(1, core.transitionWaiterCountForTest(), "the transition waiter must survive")
      move.cancel()
    }
  }
}
