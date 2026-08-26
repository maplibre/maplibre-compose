package org.maplibre.compose.map

import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.RenderBackendPair

/**
 * A second [MlnFfiMapEngine.acquireCore] with another density or backend must evict the live
 * session before it closes the core, so no session ever renders against a destroyed core.
 */
class MlnFfiMapEngineEvictionTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val errors = mutableListOf<String>()

  private val logger =
    Logger(
      config =
        StaticConfig(
          logWriterList =
            listOf(
              object : LogWriter() {
                override fun log(
                  severity: Severity,
                  message: String,
                  tag: String,
                  throwable: Throwable?,
                ) {
                  if (severity >= Severity.Error) errors += message
                }
              }
            )
        ),
      tag = "eviction-test",
    )

  private var state: MapState? = null

  @AfterTest
  fun cleanUp() {
    state?.close()
    MlnFfiApplication.resetForTest()
    FfiTestPlatform.deleteCacheFile(cacheFile)
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

  private fun engine(): MlnFfiMapEngine {
    MlnFfiApplication.configure(
      MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
    )
    val created = MapState()
    created.logger = logger
    state = created
    return created.engine as MlnFfiMapEngine
  }

  @Test
  fun a_scale_change_evicts_the_live_session_before_the_core_closes() {
    val engine = engine()
    val core1 = engine.acquireCore(1.0, LayoutDirection.Ltr, VULKAN)
    val session1 = engine.createSession(core1, VULKAN)
    val host = ObservingHostSession(core1)
    session1.onSurfaceAvailable(host)
    assertFailsWith<IllegalStateException>("a second session on the same live core must refuse") {
      engine.createSession(core1, VULKAN)
    }

    val core2 = engine.acquireCore(2.0, LayoutDirection.Ltr, VULKAN)

    assertNotSame(core1, core2, "a scale change must recreate the core")
    assertTrue(session1.isClosed, "the live session must be evicted by the second acquire")
    assertTrue(core1.isClosed, "the mismatched core must be closed")
    assertFalse(host.sawClosedCore, "the session must close before its core does")

    val session2 = engine.createSession(core2, VULKAN)
    assertFalse(session2.isClosed)
    // The view's own later dispose of the evicted session must stay a harmless no-op.
    session1.close()
    engine.releaseSession(session1)
    assertFailsWith<IllegalStateException>("the new core's session refusal must survive it") {
      engine.createSession(core2, VULKAN)
    }
    assertTrue(errors.isEmpty(), "the eviction logged errors: $errors")
  }

  @Test
  fun a_backend_change_evicts_the_live_session_before_the_core_closes() {
    val engine = engine()
    val core1 = engine.acquireCore(1.0, LayoutDirection.Ltr, VULKAN)
    val session1 = engine.createSession(core1, VULKAN)
    val host = ObservingHostSession(core1)
    session1.onSurfaceAvailable(host)

    val core2 = engine.acquireCore(1.0, LayoutDirection.Ltr, MapRenderBackend.OPENGL)

    assertNotSame(core1, core2, "a backend change must recreate the core")
    assertTrue(session1.isClosed, "the live session must be evicted by the second acquire")
    assertTrue(core1.isClosed, "the mismatched core must be closed")
    assertFalse(host.sawClosedCore, "the session must close before its core does")
    assertTrue(errors.isEmpty(), "the eviction logged errors: $errors")
  }

  private companion object {
    val VULKAN = MapRenderBackend.VULKAN
  }
}
