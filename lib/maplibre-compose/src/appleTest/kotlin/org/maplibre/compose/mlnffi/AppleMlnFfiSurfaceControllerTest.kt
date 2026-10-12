package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.map.MapExtent
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.QuartzCore.CAMetalLayer

class AppleMlnFfiSurfaceControllerTest {
  @Test
  fun destruction_before_layout_does_not_prevent_a_later_surface() = runAppleSurfaceTest {
    val renderer = RecordingRenderer()
    AppleMlnFfiSurfaceController(renderer, logger = null, onFailure = { throw it }).use { controller
      ->
      val oldLayer = CAMetalLayer()
      val layer = CAMetalLayer()
      controller.surfaceDestroyed(oldLayer).await().getOrThrow()
      controller.surfaceLayoutChanged(layer, MapExtent.fromLogical(32, 32, 1.0))
      controller.awaitActions()
      controller.surfaceDestroyed(oldLayer).await().getOrThrow()
      assertEquals(listOf("available", "resized"), renderer.events)
      controller.surfaceDestroyed(layer).await().getOrThrow()

      controller.close()
      controller.awaitClosed()
      assertEquals(listOf("available", "resized", "lost"), renderer.events)
    }
  }

  @Test
  fun terminal_failure_reports_to_the_owner_without_closing_its_map() = runAppleSurfaceTest {
    val expected = IllegalStateException("deliberate surface failure")
    var failure: Throwable? = null
    val renderer =
      object : MlnFfiMapRenderer {
        override val backend = MapRenderBackend.Metal

        override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
          throw expected
        }

        override fun onSurfaceChanged(extent: MapExtent) = Unit

        override fun onSurfaceLost(session: MlnFfiMapHostSession) = Unit

        override fun render(
          host: MlnFfiMapHostSession,
          frame: MlnFfiMapFrame,
          captureProjection: Boolean,
        ) = MlnFfiFrameResult.AwaitUpdate

        override fun close() {
          error("The presentation owner must retain control of the map")
        }
      }
    AppleMlnFfiSurfaceController(renderer, logger = null, onFailure = { failure = it }).use {
      controller ->
      controller.surfaceLayoutChanged(CAMetalLayer(), MapExtent.fromLogical(32, 32, 1.0))
      controller.awaitActions()
      controller.close()
      controller.awaitClosed()
      assertSame(expected, failure)
    }
  }

  @Test
  fun renderer_release_failure_is_reported_by_completion() = runAppleSurfaceTest {
    val expected = IllegalStateException("renderer release failed")
    val renderer =
      object : MlnFfiMapRenderer {
        override val backend = MapRenderBackend.Metal

        override fun render(
          host: MlnFfiMapHostSession,
          frame: MlnFfiMapFrame,
          captureProjection: Boolean,
        ) = MlnFfiFrameResult.AwaitUpdate

        override fun onSurfaceLost(session: MlnFfiMapHostSession) {
          throw expected
        }

        override fun close() = Unit
      }
    val controller = AppleMlnFfiSurfaceController(renderer, logger = null, onFailure = {})
    val layer = attachLayer(controller)
    val detached = controller.surfaceDestroyed(layer)
    assertSame(expected, detached.await().exceptionOrNull())
    controller.close()
    assertSame(expected, assertFailsWith<IllegalStateException> { controller.awaitClosed() })
  }

  @Test
  fun rendered_frames_do_not_replenish_recovery_attempts() {
    assertRecoveryLimit(replaceLayer = false)
  }

  @Test
  fun surface_loss_does_not_replenish_recovery_attempts() {
    assertRecoveryLimit(replaceLayer = true)
  }

  @Test
  fun a_failed_controller_ignores_a_later_layer() = withScriptedController { controller, renderer ->
    controller.withRendererAccess {
      renderer.nextError = IllegalStateException("Unrecoverable test failure")
      controller.requestFrame()
    }
    renderer.awaitFrameOrFailure()
    controller.surfaceLayoutChanged(CAMetalLayer(), Extent)
    controller.withRendererAccess {
      assertEquals(1, renderer.attachments)
      assertEquals(1, renderer.failures.size)
    }
  }

  @Test
  fun completed_frames_rearm_at_maximum_fps_and_stop_after_surface_loss() = runAppleSurfaceTest {
    val frames = Channel<ComparableTimeMark>(Channel.UNLIMITED)
    val renderer =
      object : MlnFfiMapRenderer {
        override val backend = MapRenderBackend.Metal

        override fun render(
          host: MlnFfiMapHostSession,
          frame: MlnFfiMapFrame,
          captureProjection: Boolean,
        ): MlnFfiFrameResult {
          frames.trySend(TimeSource.Monotonic.markNow())
          return MlnFfiFrameResult.Rendered()
        }

        override fun close() = Unit
      }
    val controller =
      AppleMlnFfiSurfaceController(renderer, null, maximumFps = 20, onFailure = { throw it })
    try {
      val layer = attachLayer(controller)
      val first = withTimeout(5_000L) { frames.receive() }
      val second = withTimeout(5_000L) { frames.receive() }
      assertTrue(second - first >= 50.milliseconds, "The next demand bypassed maximumFps")
      controller.surfaceDestroyed(layer).await().getOrThrow()
      controller.awaitActions()
      assertTrue(frames.tryReceive().isFailure, "A frame rendered after surface loss")
    } finally {
      controller.close()
      controller.awaitClosed()
    }
  }

  @Test
  fun inactive_surfaces_release_the_session_and_resume_on_the_same_layer() = runAppleSurfaceTest {
    val renderer = RecordingRenderer()
    val controller = AppleMlnFfiSurfaceController(renderer, null, onFailure = { throw it })
    try {
      val layer = attachLayer(controller)
      controller.setActive(false)
      controller.awaitActions()
      assertEquals(listOf("available", "resized", "lost"), renderer.events)
      controller.setActive(true)
      controller.awaitActions()
      assertEquals(listOf("available", "resized", "lost", "available"), renderer.events)
      controller.surfaceDestroyed(layer).await().getOrThrow()
      assertEquals("lost", renderer.events.last())
    } finally {
      controller.close()
      controller.awaitClosed()
    }
  }

  private fun assertRecoveryLimit(replaceLayer: Boolean) =
    withScriptedController { controller, renderer ->
      var layer = checkNotNull(renderer.layer)
      repeat(4) { attempt ->
        controller.withRendererAccess {
          renderer.nextError = MlnFfiRecoverableFrameException("Test frame failure", null)
          controller.requestFrame()
        }
        renderer.awaitFrameOrFailure()
        controller.withRendererAccess {
          assertEquals(if (attempt == 3) 1 else 0, renderer.failures.size)
          assertEquals(
            1 + minOf(attempt + 1, 3) + if (replaceLayer) attempt else 0,
            renderer.attachments,
          )
        }
        if (replaceLayer && attempt < 3) {
          controller.surfaceDestroyed(layer).await().getOrThrow()
          layer = CAMetalLayer()
          controller.surfaceLayoutChanged(layer, Extent)
          renderer.awaitFrameOrFailure()
        }
      }
    }

  /** Runs [action] after the controller rendered its first frame into a layer. */
  private fun withScriptedController(
    action: suspend (AppleMlnFfiSurfaceController, ScriptedRenderer) -> Unit
  ) = runAppleSurfaceTest {
    val renderer = ScriptedRenderer()
    val controller =
      AppleMlnFfiSurfaceController(
        renderer,
        logger = null,
        onFailure = {
          renderer.failures += it
          renderer.completed.trySend(Unit)
        },
      )
    try {
      renderer.layer = attachLayer(controller)
      renderer.awaitFrameOrFailure()
      controller.withRendererAccess {
        assertEquals(0, renderer.failures.size, "Surface initialization failed")
      }
      action(controller, renderer)
    } finally {
      controller.close()
      withTimeout(5_000L) { controller.awaitClosed() }
    }
  }

  private suspend fun attachLayer(controller: AppleMlnFfiSurfaceController): CAMetalLayer {
    val layer = CAMetalLayer()
    controller.surfaceLayoutChanged(layer, Extent)
    controller.awaitActions()
    return layer
  }

  private suspend fun AppleMlnFfiSurfaceController.awaitActions() {
    val done = CompletableDeferred<Unit>()
    assertTrue(enqueueRenderer { done.complete(Unit) })
    done.await()
  }

  /** Renders successfully unless a test queued [nextError]; never touches the layer. */
  private class ScriptedRenderer : MlnFfiMapRenderer {
    override val backend = MapRenderBackend.Metal
    var layer: CAMetalLayer? = null
    var nextError: Throwable? = null
    var attachments = 0
    private var rendered = false
    val failures = mutableListOf<Throwable>()
    val completed = Channel<Unit>(Channel.UNLIMITED)

    suspend fun awaitFrameOrFailure() {
      withTimeout(5_000L) { completed.receive() }
    }

    override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
      attachments++
      rendered = false
    }

    override fun render(
      host: MlnFfiMapHostSession,
      frame: MlnFfiMapFrame,
      captureProjection: Boolean,
    ): MlnFfiFrameResult {
      val error = nextError
      nextError = null
      if (error != null) throw error
      if (rendered) return MlnFfiFrameResult.AwaitUpdate
      rendered = true
      completed.trySend(Unit)
      return MlnFfiFrameResult.Rendered()
    }

    override fun close() = Unit
  }

  private class RecordingRenderer : MlnFfiMapRenderer {
    val events = mutableListOf<String>()
    override val backend = MapRenderBackend.Metal

    override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
      events += "available"
    }

    override fun onSurfaceChanged(extent: MapExtent) {
      events += "resized"
    }

    override fun onSurfaceLost(session: MlnFfiMapHostSession) {
      events += "lost"
    }

    override fun render(
      host: MlnFfiMapHostSession,
      frame: MlnFfiMapFrame,
      captureProjection: Boolean,
    ) = MlnFfiFrameResult.AwaitUpdate

    override fun close() = Unit
  }

  private companion object {
    val Extent = MapExtent.fromLogical(32, 32, 1.0)
  }
}

/** Lets main-dispatcher surface work run while a test awaits its completions. */
@OptIn(kotlinx.cinterop.BetaInteropApi::class)
private fun runAppleSurfaceTest(action: suspend () -> Unit) {
  val scope = CoroutineScope(Dispatchers.Main)
  val result = scope.async { action() }
  val deadline = TimeSource.Monotonic.markNow() + 30.seconds
  while (!result.isCompleted) {
    check(deadline.hasNotPassedNow()) { "The Apple surface test did not finish" }
    NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.001))
  }
  kotlinx.coroutines.runBlocking { result.await() }
}
