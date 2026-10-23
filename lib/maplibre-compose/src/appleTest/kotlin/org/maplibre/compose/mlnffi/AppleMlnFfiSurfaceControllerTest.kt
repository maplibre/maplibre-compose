package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.map.MapExtent
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
  fun completed_frames_rearm_until_surface_loss() = runAppleSurfaceTest {
    val frames = MutableStateFlow(0)
    val renderer =
      object : MlnFfiMapRenderer {
        override val backend = MapRenderBackend.Metal

        override fun render(
          host: MlnFfiMapHostSession,
          frame: MlnFfiMapFrame,
          captureProjection: Boolean,
        ): MlnFfiFrameResult {
          frames.value++
          return MlnFfiFrameResult.Rendered()
        }

        override fun close() = Unit
      }
    val controller = AppleMlnFfiSurfaceController(renderer, null, onFailure = { throw it })
    try {
      val layer = attachLayer(controller)
      withTimeout(5_000L) { frames.first { it >= 2 } }
      controller.surfaceDestroyed(layer).await().getOrThrow()
      val framesAtLoss = frames.value
      controller.awaitActions()
      assertEquals(framesAtLoss, frames.value, "A frame rendered after surface loss")
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
    action: suspend (AppleMlnFfiSurfaceController, ScriptedRenderer) -> Unit,
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

/**
 * Runs [action] with `Dispatchers.Main`, the controller's render thread, on the test scheduler.
 * Tests run on the process main thread, so the controller's render-thread checks hold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun runAppleSurfaceTest(action: suspend () -> Unit) {
  Dispatchers.setMain(StandardTestDispatcher())
  try {
    runTest { action() }
  } finally {
    Dispatchers.resetMain()
  }
}
