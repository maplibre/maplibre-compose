package org.maplibre.compose.map

import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.testing.RecordingLogger

/**
 * A second [MapEngine.acquireCore] with another density or backend must evict the live session
 * before it closes the core, so no session ever renders against a destroyed core.
 */
class MlnFfiMapEngineEvictionTest {

  private val cache = FfiTestCache()

  private val log = RecordingLogger("eviction-test")

  private var state: MapState? = null

  @AfterTest
  fun cleanUp() {
    state?.close()
    MlnFfiApplication.resetForTest()
    cache.close()
  }

  /** Records whether the core was already closed whenever the session reaches its renderer. */
  private class ObservingHostSession(private val core: MlnFfiMapCore) : MlnFfiMapHostSession {
    override val backends = RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)

    var sawClosedCore = false
      private set

    override fun requestFrame() {}

    override fun <T> withRendererAccess(action: () -> T): T {
      if (core.isClosed) sawClosedCore = true
      return action()
    }
  }

  private fun engine(): MapEngine {
    cache.configure()
    val created = MapState()
    created.logger = log.logger
    state = created
    return created.engine
  }

  /** Asserts the eviction contract: the live session closes first, and the core closes after. */
  private fun assertEvicted(
    session: MlnFfiMapSession,
    host: ObservingHostSession,
    core: MlnFfiMapCore,
  ) {
    assertTrue(session.isClosed, "the live session must be evicted by the second acquire")
    assertTrue(core.isClosed, "the mismatched core must be closed")
    assertFalse(host.sawClosedCore, "the session must close before its core does")
  }

  /**
   * One engine walks both eviction triggers: a scale change evicts the first live session, and a
   * backend change then evicts the session on the replacement core, each closing the session before
   * its core.
   */
  @Test
  fun a_scale_change_and_then_a_backend_change_each_evict_the_live_session_before_the_core_closes() {
    val engine = engine()
    val core1 = engine.acquireCore(1.0, LayoutDirection.Ltr, VULKAN)
    val session1 = engine.createSession(core1, VULKAN)
    val host1 = ObservingHostSession(core1)
    session1.onSurfaceAvailable(host1)
    assertFailsWith<IllegalStateException>("a second session on the same live core must refuse") {
      engine.createSession(core1, VULKAN)
    }

    val core2 = engine.acquireCore(2.0, LayoutDirection.Ltr, VULKAN)

    assertNotSame(core1, core2, "a scale change must recreate the core")
    assertEvicted(session1, host1, core1)

    val session2 = engine.createSession(core2, VULKAN)
    assertFalse(session2.isClosed)
    val host2 = ObservingHostSession(core2)
    session2.onSurfaceAvailable(host2)
    // The view's own later dispose of the evicted session must stay a harmless no-op.
    session1.close()
    engine.releaseSession(session1)
    assertFailsWith<IllegalStateException>("the new core's session refusal must survive it") {
      engine.createSession(core2, VULKAN)
    }

    val core3 = engine.acquireCore(2.0, LayoutDirection.Ltr, MapRenderBackend.OPENGL)

    assertNotSame(core2, core3, "a backend change must recreate the core")
    assertEvicted(session2, host2, core2)
    assertTrue(log.messages.isEmpty(), "the evictions logged errors: ${log.messages}")
  }

  private companion object {
    val VULKAN = MapRenderBackend.VULKAN
  }
}
