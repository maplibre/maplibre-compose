package org.maplibre.compose.map

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.layers.BackgroundLayerDescriptor
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.createSnapshotTarget
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.OpRecordingStyleBinding

/** The engine's retained core across snapshots and session detach. */
class MlnFfiRetainedCoreTest {

  private val cache = FfiTestCache()

  private var state: MapState? = null

  @AfterTest
  fun cleanUp() {
    state?.close()
    MlnFfiApplication.resetForTest()
    cache.close()
  }

  private fun bareState(): MapState {
    cache.configure()
    return MapState().also { state = it }
  }

  @Test
  fun a_snapshot_recreates_a_retained_core_whose_backend_differs() {
    val state = bareState()
    state.baseStyle = RED_STYLE
    // No packaged snapshot target renders OpenGL, so the retained backend always mismatches.
    val stale = state.engine.acquireCore(1.0, LayoutDirection.Ltr, MapRenderBackend.OPENGL)

    val image = runBlocking {
      state.captureStillImage(width = 10.dp, height = 10.dp, timeout = 60.seconds)
    }

    assertEquals(10, image.width)
    assertTrue(stale.isClosed, "the mismatched core must be evicted, not rendered with")
    assertNotSame<MlnFfiMapCore?>(stale, state.engine.core)
  }

  @Test
  fun a_snapshot_neutralizes_retained_session_constraints() {
    val state = bareState()
    state.baseStyle = RED_STYLE
    val backend = createSnapshotTarget().use { it.backend }
    val core = state.engine.acquireCore(1.0, LayoutDirection.Ltr, backend)
    core.start()
    // The constraint a departed session left behind, tighter than the recorded camera.
    core.setMaxZoom(2.0)
    runBlocking { state.setCamera(CameraPosition(zoom = 5.0)) }

    runBlocking { state.captureStillImage(width = 20.dp, height = 20.dp, timeout = 60.seconds) }

    assertSame(core, state.engine.core, "a matching backend keeps the retained core")
    assertEquals(5.0, core.getCameraPosition().zoom, 0.01, "the snapshot must not clamp the zoom")
    assertFalse(
      core.hasAttachedViewportForTest(),
      "the snapshot target's dimensions must not survive it",
    )
  }

  @Test
  fun detach_keeps_the_style_collections_while_the_engine_retains_the_core() {
    val state = bareState()
    state.engine.acquireCore(1.0, LayoutDirection.Ltr, MapRenderBackend.VULKAN)
    state.setStyleContent {}
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(
      adapter,
      OpRecordingStyleBinding(
        baseSources =
          listOf(
            RasterSource(
              "base-src",
              listOf("https://example.invalid/{z}/{x}/{y}.png"),
              TileSetOptions(),
            )
          ),
        baseLayers = listOf(BackgroundLayerDescriptor("base-layer")),
      ),
    )
    assertTrue("base-src" in state.sources.ids)
    assertTrue("base-layer" in state.layers.ids)

    state.detachSession()

    assertTrue("base-src" in state.sources.ids, "a retained core keeps the source snapshot")
    assertTrue("base-layer" in state.layers.ids, "sources and layers persist together")
  }

  @Test
  fun a_close_ends_a_camera_animation_suspended_across_frames() {
    val state = bareState()
    val core = state.engine.acquireCore(1.0, LayoutDirection.Ltr, MapRenderBackend.VULKAN)
    core.start()
    state.attachSession(core)
    runBlocking {
      val move =
        async(Dispatchers.Default) {
          state.animateCamera(CameraPosition(zoom = 5.0), duration = 60.seconds)
        }
      // With no render session, no frame steps the transition, so only the close can end it.
      while (core.transitionWaiterCountForTest() == 0) {
        check(!move.isCompleted) { "the animation must stay suspended until the close" }
        delay(10)
      }
      val elapsed = measureTime {
        state.close()
        move.await()
      }
      assertTrue(elapsed < 10.seconds, "the close ended the animation only after $elapsed")
    }
  }

  @Test
  fun releasing_a_session_resets_the_attached_viewport() {
    val state = bareState()
    val core = state.engine.acquireCore(1.0, LayoutDirection.Ltr, MapRenderBackend.VULKAN)
    core.publishAttachedViewport()
    assertTrue(core.hasAttachedViewportForTest())

    val session = state.engine.createSession(core, MapRenderBackend.VULKAN)
    state.engine.releaseSession(session)
    session.close()

    assertFalse(
      core.hasAttachedViewportForTest(),
      "a departed target's dimensions must not satisfy the next bounds fit",
    )
  }

  private companion object {
    val RED_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#ff0000"}}]}
        """
          .trimIndent()
      )
  }
}
