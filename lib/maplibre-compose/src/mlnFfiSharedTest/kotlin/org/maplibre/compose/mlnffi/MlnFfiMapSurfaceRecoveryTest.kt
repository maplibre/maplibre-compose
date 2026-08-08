package org.maplibre.compose.mlnffi

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A frame that fails once must not blank the map forever, and retries must be bounded. */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapSurfaceRecoveryTest {

  @Test
  fun a_host_creation_failure_reaches_the_surface_state_observer_immediately() =
    runFfiComposeUiTest {
      val cause = IllegalStateException("deliberate host creation failure")
      val factory =
        object : MlnFfiMapHostFactory {
          override val description = "deliberately failing test host"
          override val supportedBackends =
            setOf(RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL))

          override fun create(producer: MapRenderBackend): MlnFfiMapHostResult =
            MlnFfiMapHostResult.Failed("could not create the deliberate test host", cause)
        }
      var observed: MlnFfiMapSurfaceState = MlnFfiMapSurfaceState.Initializing
      val renderer = RecordingRenderer()

      setContent {
        CompositionLocalProvider(LocalMlnFfiMapSurfaceStateObserver provides { observed = it }) {
          MlnFfiMapSurface(
            renderer = renderer,
            runtimeBackends = setOf(MapRenderBackend.VULKAN),
            factory = factory,
            modifier = Modifier.size(64.dp),
          )
        }
      }
      waitUntil(timeoutMillis = 1_000) { observed is MlnFfiMapSurfaceState.Failed }

      val failed = assertIs<MlnFfiMapSurfaceState.Failed>(observed)
      assertEquals("could not create the deliberate test host", failed.diagnostic)
      assertSame(cause, failed.cause)
      assertEquals(1, renderer.closeCount, "terminal host creation should stop map work")
    }

  @Test
  fun a_host_that_fails_one_acquire_recovers_and_renders_a_later_frame() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.failingAcquires = 1 })
    val states = mutableListOf<MlnFfiMapSurfaceState>()

    setSurfaceContent(renderer, factory) { states += it }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

    val host = factory.created.single()
    assertEquals(1, renderer.surfaceLostCount, "the surface loss should have been reported once")
    assertEquals(
      listOf("onSurfaceAvailable", "onSurfaceLost", "onSurfaceAvailable"),
      renderer.lifecycle,
      "recovery should hand the renderer a surface back after taking it away",
    )
    assertTrue(
      host.acquireCount >= 2,
      "the failed acquire should have been retried, but only ${host.acquireCount} happened",
    )
    assertTrue(
      states.none { it is MlnFfiMapSurfaceState.Failed },
      "a recovered frame should not have latched the surface: $states",
    )
  }

  @Test
  fun a_host_without_a_context_skips_past_the_failure_bound_and_then_renders() =
    runFfiComposeUiTest {
      val renderer = RecordingRenderer()
      val factory =
        FakeMlnFfiMapHostFactory(
          configureHost = { it.notReadyAcquires = MAX_RECOVERY_ATTEMPTS + 2 }
        )
      val states = mutableListOf<MlnFfiMapSurfaceState>()

      setSurfaceContent(renderer, factory) { states += it }
      waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

      assertEquals(0, renderer.surfaceLostCount)
      assertEquals(MAX_RECOVERY_ATTEMPTS + 3, factory.created.single().acquireCount)
      assertTrue(states.none { it is MlnFfiMapSurfaceState.Failed })
    }

  @Test
  fun not_ready_between_failures_neither_spends_nor_resets_the_recovery_budget() =
    runFfiComposeUiTest {
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
      var latest: MlnFfiMapSurfaceState = MlnFfiMapSurfaceState.Initializing

      setSurfaceContent(renderer, factory) { latest = it }
      waitUntil(timeoutMillis = TIMEOUT_MILLIS) { latest is MlnFfiMapSurfaceState.Failed }

      assertEquals(MAX_RECOVERY_ATTEMPTS * 2 + 1, factory.created.single().acquireCount)
      assertEquals(MAX_RECOVERY_ATTEMPTS, renderer.surfaceLostCount)
      assertEquals(1, renderer.closeCount)
    }

  /** The other half of a frame: the renderer, rather than the host, is what throws. */
  @Test
  fun a_renderer_that_fails_one_frame_recovers_and_renders_a_later_frame() = runFfiComposeUiTest {
    val renderer = RecordingRenderer(failingRenders = 1)
    val factory = FakeMlnFfiMapHostFactory()
    val states = mutableListOf<MlnFfiMapSurfaceState>()

    setSurfaceContent(renderer, factory) { states += it }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

    assertEquals(1, renderer.surfaceLostCount, "the surface loss should have been reported once")
    assertTrue(
      states.none { it is MlnFfiMapSurfaceState.Failed },
      "a recovered frame should not have latched the surface: $states",
    )
  }

  /** A device that never comes back; only the call count can show the retry bound. */
  @Test
  fun a_host_that_fails_every_acquire_gives_up_after_the_retry_bound() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory(configureHost = { it.failingAcquires = Int.MAX_VALUE })
    var latest: MlnFfiMapSurfaceState = MlnFfiMapSurfaceState.Initializing

    setSurfaceContent(renderer, factory) { latest = it }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { latest is MlnFfiMapSurfaceState.Failed }

    val host = factory.created.single()
    assertEquals(
      MAX_RECOVERY_ATTEMPTS + 1,
      host.acquireCount,
      "the surface should have stopped after ${MAX_RECOVERY_ATTEMPTS} recovery attempts",
    )
    assertEquals(
      MAX_RECOVERY_ATTEMPTS,
      renderer.surfaceLostCount,
      "each retry should have gone through the surface-loss path",
    )
    val failed = assertIs<MlnFfiMapSurfaceState.Failed>(latest)
    assertTrue(
      failed.diagnostic.contains("$MAX_RECOVERY_ATTEMPTS attempts"),
      "the diagnostic should say how many attempts were made: ${failed.diagnostic}",
    )
    assertEquals(1, renderer.closeCount, "giving up should stop the renderer immediately")

    waitForIdle()
    assertEquals(
      MAX_RECOVERY_ATTEMPTS + 1,
      host.acquireCount,
      "a latched surface should not keep acquiring frames",
    )
  }

  /**
   * A failure a new surface cannot fix: a session that has closed itself skips every later frame
   * silently, so retrying would replace a reported failure with a blank map.
   */
  @Test
  fun a_fatal_renderer_failure_latches_without_retrying() = runFfiComposeUiTest {
    val renderer = RecordingRenderer(failingRenders = 1, fatal = true)
    val factory = FakeMlnFfiMapHostFactory()
    var latest: MlnFfiMapSurfaceState = MlnFfiMapSurfaceState.Initializing

    setSurfaceContent(renderer, factory) { latest = it }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { latest is MlnFfiMapSurfaceState.Failed }

    assertEquals(0, renderer.surfaceLostCount, "a fatal failure should not rebuild the surface")
    assertEquals(1, factory.created.single().acquireCount, "a fatal failure should not be retried")
    assertEquals(1, renderer.closeCount, "a fatal failure should stop the renderer immediately")
  }

  @Test
  fun a_resize_failure_closes_the_renderer_immediately() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    val size = mutableStateOf(64.dp)
    var latest: MlnFfiMapSurfaceState = MlnFfiMapSurfaceState.Initializing

    setContent {
      MlnFfiMapSurface(
        renderer = renderer,
        runtimeBackends = setOf(MapRenderBackend.VULKAN),
        factory = factory,
        modifier = Modifier.size(size.value),
        onStateChanged = { latest = it },
      )
    }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

    renderer.failingSurfaceChanges = 1
    size.value = 96.dp
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { latest is MlnFfiMapSurfaceState.Failed }

    assertEquals(1, renderer.closeCount, "a failed resize should stop the renderer immediately")
    waitForIdle()
    assertEquals(1, renderer.closeCount, "the failed surface should close the renderer only once")
  }

  @Test
  fun an_unavailable_host_closes_the_renderer_immediately() = runFfiComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeMlnFfiMapHostFactory(supportedBackends = emptySet())
    val showSurface = mutableStateOf(true)

    setContent {
      if (showSurface.value) {
        MlnFfiMapSurface(
          renderer = renderer,
          runtimeBackends = setOf(MapRenderBackend.VULKAN),
          factory = factory,
          modifier = Modifier.size(64.dp),
        )
      }
    }
    waitForIdle()
    assertEquals(1, renderer.closeCount)

    showSurface.value = false
    waitForIdle()
    assertEquals(1, renderer.closeCount, "disposal should not close the renderer twice")
  }

  private fun ComposeUiTest.setSurfaceContent(
    renderer: MlnFfiMapRenderer,
    factory: MlnFfiMapHostFactory,
    onStateChanged: (MlnFfiMapSurfaceState) -> Unit,
  ) {
    setContent {
      MlnFfiMapSurface(
        renderer = renderer,
        runtimeBackends = setOf(MapRenderBackend.VULKAN),
        factory = factory,
        // A surface with no extent never acquires a frame, so every test here would pass vacuously.
        modifier = Modifier.size(64.dp),
        logger = Logger.withTag("surface-recovery-test"),
        onStateChanged = onStateChanged,
      )
    }
  }

  /** A renderer that records what the surface did to it, and fails on demand. */
  private class RecordingRenderer(
    private var failingRenders: Int = 0,
    private val fatal: Boolean = false,
  ) : MlnFfiMapRenderer {

    override val backend: MapRenderBackend = MapRenderBackend.VULKAN

    /** Surface lifecycle calls in order. */
    val lifecycle: MutableList<String> = mutableListOf()

    var renderedFrames: Int = 0
      private set

    var surfaceLostCount: Int = 0
      private set

    var closeCount: Int = 0
      private set

    var failingSurfaceChanges: Int = 0

    override fun onSurfaceChanged(extent: MlnFfiMapExtent) {
      if (failingSurfaceChanges > 0) {
        failingSurfaceChanges--
        throw IllegalStateException(
          "the renderer cannot resize to ${extent.width}x${extent.height}"
        )
      }
    }

    override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
      lifecycle += "onSurfaceAvailable"
    }

    override fun onSurfaceLost() {
      surfaceLostCount++
      lifecycle += "onSurfaceLost"
    }

    override fun render(frame: MlnFfiMapFrame): MlnFfiFrameResult {
      if (failingRenders > 0) {
        failingRenders--
        val message = "the renderer lost its device on frame ${frame.frameId}"
        throw if (fatal) MlnFfiFatalFrameException(message, null)
        else IllegalStateException(message)
      }
      renderedFrames++
      return MlnFfiFrameResult.RENDERED
    }

    override fun close() {
      closeCount++
    }
  }

  private companion object {
    /** Mirrors `MAX_FRAME_RECOVERY_ATTEMPTS`, which is private to the surface. */
    const val MAX_RECOVERY_ATTEMPTS = 3

    const val TIMEOUT_MILLIS = 10_000L
  }
}
