package org.maplibre.compose.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A frame that fails once must not blank the map forever.
 *
 * This is the sleep/wake failure, reproduced without a machine that sleeps. Losing the graphics
 * contexts under the map makes the next frame throw, and the surface used to read that single throw
 * as terminal: it latched into `Failed`, stopped drawing, and left a map that was otherwise
 * perfectly alive showing nothing, with both its threads parked and one line in the log.
 *
 * The fake host is the right vehicle because none of this is about the GPU. What is under test is
 * whether the surface tells the renderer the surface was lost, gives it back, and asks for the
 * frame that proves it worked — and whether it knows when to stop.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopMapSurfaceRecoveryTest {

  @Test
  fun `a host that fails one acquire recovers and renders a later frame`() = runComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeDesktopMapHostFactory(configureHost = { it.failingAcquires = 1 })
    val states = mutableListOf<DesktopMapSurfaceState>()

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
      states.none { it is DesktopMapSurfaceState.Failed },
      "a recovered frame should not have latched the surface: $states",
    )
  }

  /** The other half of a frame: the renderer, rather than the host, is what throws. */
  @Test
  fun `a renderer that fails one frame recovers and renders a later frame`() = runComposeUiTest {
    val renderer = RecordingRenderer(failingRenders = 1)
    val factory = FakeDesktopMapHostFactory()
    val states = mutableListOf<DesktopMapSurfaceState>()

    setSurfaceContent(renderer, factory) { states += it }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { renderer.renderedFrames > 0 }

    assertEquals(1, renderer.surfaceLostCount, "the surface loss should have been reported once")
    assertTrue(
      states.none { it is DesktopMapSurfaceState.Failed },
      "a recovered frame should not have latched the surface: $states",
    )
  }

  /**
   * A device that never comes back.
   *
   * The count is the assertion that matters. A surface that retried forever would still look
   * healthy in every other respect — it would just redraw and fail for as long as the window is
   * open — so the only way to see the bound is to count the calls it made.
   */
  @Test
  fun `a host that fails every acquire gives up after the retry bound`() = runComposeUiTest {
    val renderer = RecordingRenderer()
    val factory = FakeDesktopMapHostFactory(configureHost = { it.failingAcquires = Int.MAX_VALUE })
    var latest: DesktopMapSurfaceState = DesktopMapSurfaceState.Initializing

    setSurfaceContent(renderer, factory) { latest = it }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { latest is DesktopMapSurfaceState.Failed }

    val host = factory.created.single()
    // One frame that failed, then one retry per attempt the bound allows, and nothing after the
    // surface latched.
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
    val failed = assertIs<DesktopMapSurfaceState.Failed>(latest)
    assertTrue(
      failed.diagnostic.contains("$MAX_RECOVERY_ATTEMPTS attempts"),
      "the diagnostic should say how many attempts were made: ${failed.diagnostic}",
    )

    // Nothing further, even after the composition has had every chance to draw again.
    waitForIdle()
    assertEquals(
      MAX_RECOVERY_ATTEMPTS + 1,
      host.acquireCount,
      "a latched surface should not keep acquiring frames",
    )
  }

  /**
   * A failure a new surface cannot fix.
   *
   * The map's own runtime dying is reported through the same channel as a lost device — a throw out
   * of `render` — and looks identical from here. Retrying it would replace a reported failure with
   * a blank map, because a session that has closed itself skips every frame afterwards without
   * throwing anything for the surface to notice.
   */
  @Test
  fun `a fatal renderer failure latches without retrying`() = runComposeUiTest {
    val renderer = RecordingRenderer(failingRenders = 1, fatal = true)
    val factory = FakeDesktopMapHostFactory()
    var latest: DesktopMapSurfaceState = DesktopMapSurfaceState.Initializing

    setSurfaceContent(renderer, factory) { latest = it }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { latest is DesktopMapSurfaceState.Failed }

    assertEquals(0, renderer.surfaceLostCount, "a fatal failure should not rebuild the surface")
    assertEquals(1, factory.created.single().acquireCount, "a fatal failure should not be retried")
  }

  private fun ComposeUiTest.setSurfaceContent(
    renderer: DesktopMapRenderer,
    factory: DesktopMapHostFactory,
    onStateChanged: (DesktopMapSurfaceState) -> Unit,
  ) {
    setContent {
      DesktopMapSurface(
        renderer = renderer,
        runtimeBackends = setOf(MapRenderBackend.VULKAN),
        factory = factory,
        // A fixed size, because a surface with no extent never acquires a frame and every test here
        // would pass by doing nothing.
        modifier = Modifier.size(64.dp),
        logger = Logger.withTag("surface-recovery-test"),
        onStateChanged = onStateChanged,
      )
    }
  }

  /**
   * A renderer that records what the surface did to it, and fails on demand.
   *
   * Deliberately not a [org.maplibre.compose.map.DesktopMapSession]: the question is what the
   * surface does with a failure, and a real session would answer it with a MapLibre runtime, a
   * thread, and a style load in the way.
   */
  private class RecordingRenderer(
    private var failingRenders: Int = 0,
    private val fatal: Boolean = false,
  ) : DesktopMapRenderer {

    override val backend: MapRenderBackend = MapRenderBackend.VULKAN

    /** Surface lifecycle calls in order, which is what recovery is made of. */
    val lifecycle: MutableList<String> = mutableListOf()

    var renderedFrames: Int = 0
      private set

    var surfaceLostCount: Int = 0
      private set

    override fun onSurfaceAvailable(session: DesktopMapHostSession) {
      lifecycle += "onSurfaceAvailable"
    }

    override fun onSurfaceLost() {
      surfaceLostCount++
      lifecycle += "onSurfaceLost"
    }

    override fun render(frame: DesktopMapFrame): DesktopFrameResult {
      if (failingRenders > 0) {
        failingRenders--
        val message = "the renderer lost its device on frame ${frame.frameId}"
        throw if (fatal) DesktopMapFatalFrameException(message, null)
        else IllegalStateException(message)
      }
      renderedFrames++
      return DesktopFrameResult.RENDERED
    }

    override fun close() {}
  }

  private companion object {
    /** Mirrors `MAX_FRAME_RECOVERY_ATTEMPTS`, which is private to the surface. */
    const val MAX_RECOVERY_ATTEMPTS = 3

    const val TIMEOUT_MILLIS = 10_000L
  }
}
