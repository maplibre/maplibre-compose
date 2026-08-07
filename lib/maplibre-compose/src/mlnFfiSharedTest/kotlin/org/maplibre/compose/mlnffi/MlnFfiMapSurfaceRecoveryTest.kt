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

      setContent {
        CompositionLocalProvider(LocalMlnFfiMapSurfaceStateObserver provides { observed = it }) {
          MlnFfiMapSurface(
            renderer = RecordingRenderer(),
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
  }

  @Test
  fun an_unavailable_host_still_closes_the_renderer() = runFfiComposeUiTest {
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
    showSurface.value = false
    waitForIdle()

    assertEquals(1, renderer.closeCount)
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
