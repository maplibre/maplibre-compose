package org.maplibre.compose.map

import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.RecordingMlnFfiMapHostSession

/**
 * [MlnFfiMapSession] host-session calls without starting the native loop. Idle and repaint counts
 * that still need mbgl stay in [MlnFfiMapIdleTest] and [MlnFfiMapRepaintTest].
 */
class MlnFfiMapHostSessionRequestTest {

  @Test
  fun on_surface_available_requests_a_frame() = runTest {
    withSession { session ->
      val host = RecordingMlnFfiMapHostSession()
      session.onSurfaceAvailable(host)
      assertEquals(1, host.requestFrameCount)
      assertEquals(listOf("requestFrame"), host.calls)
    }
  }

  @Test
  fun on_surface_lost_then_available_requests_another_frame() = runTest {
    withSession { session ->
      val host = RecordingMlnFfiMapHostSession()
      session.onSurfaceAvailable(host)
      session.onSurfaceChanged(MapExtent.fromPhysical(64, 64, 1.0))
      session.onSurfaceLost()
      session.onSurfaceAvailable(host)
      assertEquals(3, host.requestFrameCount)
      assertTrue(host.calls.all { it == "requestFrame" })
    }
  }

  private suspend fun TestScope.withSession(body: suspend (MlnFfiMapSession) -> Unit) {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState()
    val session =
      MlnFfiMapSession(
        lifecycleAuthority = state.lifecycle,
        callbacks = EmptyMapAdapterCallbacks,
        logger = null,
        renderBackend = MapRenderBackend.VULKAN,
        layoutDirection = LayoutDirection.Ltr,
        cacheFile = Path(SystemTemporaryDirectory, "unused-host-session-request.db"),
      )
    try {
      body(session)
    } finally {
      session.close()
      session.awaitClosed()
      state.close()
      state.awaitClosed()
      runtime.close()
      runtime.awaitClosed()
    }
  }
}
