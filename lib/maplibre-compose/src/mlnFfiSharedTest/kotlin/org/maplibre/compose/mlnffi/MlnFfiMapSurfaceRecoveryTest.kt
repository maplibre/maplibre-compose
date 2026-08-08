package org.maplibre.compose.mlnffi

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A frame that fails once must not blank the map forever, and retries must be bounded. */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapSurfaceRecoveryTest {

  @Test
  fun a_host_creation_failure_closes_the_renderer_once() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val showSurface = mutableStateOf(true)
    val failure =
      MlnFfiMapHostResult.Failed(
        "could not create the deliberate test host",
        IllegalStateException("deliberate host creation failure"),
      )

    setContent {
      if (showSurface.value) {
        MlnFfiMapSurface(renderer, failure, Modifier.size(64.dp))
      }
    }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }
    showSurface.value = false
    waitForIdle()
    assertEquals(1, renderer.closeCount)
  }

  @Test
  fun a_host_that_fails_one_acquire_recovers_and_renders_a_later_frame() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.failingAcquires = 1 })

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

    val host = factory.created.single()
    assertEquals(1, renderer.surfaceLostCount)
    assertEquals(
      listOf("onSurfaceAvailable", "onSurfaceLost", "onSurfaceAvailable"),
      renderer.lifecycle,
    )
    assertTrue(host.acquireCount >= 2)
    assertEquals(0, renderer.closeCount)
  }

  @Test
  fun extended_not_ready_does_not_consume_recovery() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory =
      FakeMlnFfiMapHostFactory(configureHost = { it.notReadyAcquires = MAX_RECOVERY_ATTEMPTS + 20 })

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

    assertEquals(0, renderer.surfaceLostCount)
    assertEquals(MAX_RECOVERY_ATTEMPTS + 21, factory.created.single().acquireCount)
    assertEquals(0, renderer.closeCount)
  }

  @Test
  fun not_ready_between_failures_neither_spends_nor_resets_recovery() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory =
      FakeMlnFfiMapHostFactory(
        configureHost = { host ->
          repeat(MAX_RECOVERY_ATTEMPTS) {
            host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.FAILURE
            host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.NOT_READY
          }
          host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.FAILURE
        }
      )

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }

    assertEquals(MAX_RECOVERY_ATTEMPTS * 2 + 1, factory.created.single().acquireCount)
    assertEquals(MAX_RECOVERY_ATTEMPTS, renderer.surfaceLostCount)
  }

  @Test
  fun a_successful_render_resets_recovery() = runFfiComposeUiTest {
    val renderer = RecordingRenderer(requestAnotherFrame = true)
    val factory =
      FakeMlnFfiMapHostFactory(
        configureHost = { host ->
          host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.FAILURE
          host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.ACQUIRED
          repeat(MAX_RECOVERY_ATTEMPTS + 1) {
            host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.FAILURE
          }
        }
      )

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }

    assertEquals(MAX_RECOVERY_ATTEMPTS + 1, renderer.surfaceLostCount)
    assertEquals(MAX_RECOVERY_ATTEMPTS + 3, factory.created.single().acquireCount)
  }

  @Test
  fun a_renderer_that_fails_one_frame_recovers() = runFfiComposeUiTest {
    val renderer = RecordingRenderer(failingRenders = 1)
    val factory = FakeMlnFfiMapHostFactory()

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

    assertEquals(1, renderer.surfaceLostCount)
    assertEquals(0, renderer.closeCount)
  }

  @Test
  fun repeated_acquire_failure_stops_at_the_bound() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.failingAcquires = Int.MAX_VALUE })

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }

    val host = factory.created.single()
    assertEquals(MAX_RECOVERY_ATTEMPTS + 1, host.acquireCount)
    assertEquals(MAX_RECOVERY_ATTEMPTS, renderer.surfaceLostCount)
    waitForIdle()
    assertEquals(MAX_RECOVERY_ATTEMPTS + 1, host.acquireCount)
    assertEquals(1, renderer.closeCount)
  }

  @Test
  fun unexpected_renderer_failure_stops_without_retrying() = runFfiComposeUiTest {
    val renderer = RecordingRenderer(failingRenders = 1, unexpectedFailure = true)
    val factory = FakeMlnFfiMapHostFactory()

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }

    assertEquals(0, renderer.surfaceLostCount)
    assertEquals(1, factory.created.single().acquireCount)
  }

  @Test
  fun unexpected_host_failure_stops_without_retrying() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory =
      FakeMlnFfiMapHostFactory(
        configureHost = { host ->
          host.acquireOutcomes += FakeMlnFfiMapHost.AcquireOutcome.UNEXPECTED_FAILURE
        }
      )

    setSurfaceContent(renderer, factory)
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }

    assertEquals(0, renderer.surfaceLostCount)
    assertEquals(1, factory.created.single().acquireCount)
  }

  @Test
  fun resize_failure_closes_the_renderer_once() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    val size = mutableStateOf(64.dp)
    val hostResult = factory.create()

    setContent { MlnFfiMapSurface(renderer, hostResult, Modifier.size(size.value)) }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }
    renderer.failingSurfaceChanges = 1
    size.value = 96.dp
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.closeCount == 1 }
    waitForIdle()
    assertEquals(1, renderer.closeCount)
  }

  private fun ComposeUiTest.setSurfaceContent(
    renderer: MlnFfiMapRenderer,
    factory: FakeMlnFfiMapHostFactory,
  ) {
    val hostResult = factory.create()
    setContent {
      MlnFfiMapSurface(
        renderer = renderer,
        hostResult = hostResult,
        modifier = Modifier.size(64.dp),
        logger = Logger.withTag("surface-recovery-test"),
      )
    }
  }

  private class RecordingRenderer(
    private var failingRenders: Int = 0,
    private val unexpectedFailure: Boolean = false,
    private val requestAnotherFrame: Boolean = false,
  ) : MlnFfiMapRenderer {
    override val backend: MapRenderBackend = MapRenderBackend.VULKAN
    val lifecycle: MutableList<String> = mutableListOf()
    var renderedFrames = 0
      private set

    var surfaceLostCount = 0
      private set

    var closeCount = 0
      private set

    var failingSurfaceChanges = 0
    private var hostSession: MlnFfiMapHostSession? = null

    override fun onSurfaceChanged(extent: MlnFfiMapExtent) {
      if (failingSurfaceChanges > 0) {
        failingSurfaceChanges--
        throw IllegalStateException("cannot resize to ${extent.width}x${extent.height}")
      }
    }

    override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
      lifecycle += "onSurfaceAvailable"
      hostSession = session
    }

    override fun onSurfaceLost() {
      surfaceLostCount++
      lifecycle += "onSurfaceLost"
    }

    override fun render(frame: MlnFfiMapFrame): MlnFfiFrameResult {
      if (failingRenders > 0) {
        failingRenders--
        val error = "renderer lost its device on frame ${frame.frameId}"
        throw if (unexpectedFailure) IllegalStateException(error)
        else MlnFfiRecoverableFrameException(error, null)
      }
      renderedFrames++
      if (requestAnotherFrame) hostSession?.requestFrame()
      return MlnFfiFrameResult.RENDERED
    }

    override fun close() {
      closeCount++
    }
  }

  private companion object {
    const val MAX_RECOVERY_ATTEMPTS = 3
    const val TIMEOUT_MILLIS = 10_000L
  }
}
