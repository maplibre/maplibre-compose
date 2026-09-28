package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.map.MapExtent
import platform.QuartzCore.CAMetalLayer

class AppleMlnFfiSurfaceControllerTest {
  @Test
  fun destruction_before_layout_does_not_prevent_a_later_surface() = runBlocking {
    val renderer = RecordingRenderer()
    AppleMlnFfiSurfaceController(renderer, logger = null, onFailure = { throw it }).use { controller
      ->
      val oldLayer = CAMetalLayer()
      val layer = CAMetalLayer()
      controller.surfaceDestroyed(oldLayer).await().getOrThrow()
      controller.surfaceLayoutChanged(layer, MapExtent.fromLogical(32, 32, 1.0))
      controller.withRendererAccess {}
      controller.surfaceDestroyed(oldLayer).await().getOrThrow()
      assertEquals(listOf("available", "resized"), renderer.events)
      controller.surfaceDestroyed(layer).await().getOrThrow()

      controller.close()
      controller.awaitClosed()
      assertEquals(listOf("available", "resized", "lost"), renderer.events)
    }
  }

  @Test
  fun terminal_failure_reports_to_the_owner_without_closing_its_map() = runBlocking {
    val expected = IllegalStateException("deliberate surface failure")
    var failure: Throwable? = null
    val renderer =
      object : MlnFfiMapRenderer {
        override val backend = MapRenderBackend.METAL

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
      controller.withRendererAccess {}
      controller.close()
      controller.awaitClosed()
      assertSame(expected, failure)
    }
  }

  @Test
  fun close_returns_while_renderer_release_is_pending() = runBlocking {
    val releasing = TestLatch(1)
    val release = TestLatch(1)
    val releasedBeforeTimeout = CompletableDeferred<Boolean>()
    var releases = 0
    val renderer =
      object : MlnFfiMapRenderer {
        override val backend = MapRenderBackend.METAL

        override fun render(
          host: MlnFfiMapHostSession,
          frame: MlnFfiMapFrame,
          captureProjection: Boolean,
        ) = MlnFfiFrameResult.AwaitUpdate

        override fun onSurfaceLost(session: MlnFfiMapHostSession) {
          releases++
          releasing.countDown()
          releasedBeforeTimeout.complete(release.await(5_000L))
        }

        override fun close() = Unit
      }
    val controller = AppleMlnFfiSurfaceController(renderer, logger = null, onFailure = { throw it })
    val layer = attachLayer(controller)
    try {
      val detached = controller.surfaceDestroyed(layer)
      assertTrue(releasing.await(5_000L))
      assertFalse(detached.isCompleted)
      controller.close()
      val closed = async(start = CoroutineStart.UNDISPATCHED) { controller.awaitClosed() }
      assertFalse(closed.isCompleted)
      release.countDown()
      assertTrue(releasedBeforeTimeout.await(), "close blocked until the renderer timed out")
      withTimeout(5_000L) {
        detached.await().getOrThrow()
        closed.await()
      }
      controller.surfaceDestroyed(layer).await().getOrThrow()
      controller.close()
      assertEquals(1, releases)
    } finally {
      release.countDown()
      controller.close()
      withTimeout(5_000L) { controller.awaitClosed() }
    }
  }

  @Test
  fun renderer_release_failure_is_reported_by_completion() = runBlocking {
    val expected = IllegalStateException("renderer release failed")
    val renderer =
      object : MlnFfiMapRenderer {
        override val backend = MapRenderBackend.METAL

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
    controller.surfaceLayoutChanged(CAMetalLayer(), EXTENT)
    controller.withRendererAccess {
      assertEquals(1, renderer.attachments)
      assertEquals(1, renderer.failures.size)
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
          controller.surfaceLayoutChanged(layer, EXTENT)
          renderer.awaitFrameOrFailure()
        }
      }
    }

  /** Runs [action] after the controller rendered its first frame into a layer. */
  private fun withScriptedController(
    action: suspend (AppleMlnFfiSurfaceController, ScriptedRenderer) -> Unit
  ) = runBlocking {
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

  private fun attachLayer(controller: AppleMlnFfiSurfaceController): CAMetalLayer {
    val layer = CAMetalLayer()
    controller.surfaceLayoutChanged(layer, EXTENT)
    controller.withRendererAccess {}
    return layer
  }

  /** Renders successfully unless a test queued [nextError]; never touches the layer. */
  private class ScriptedRenderer : MlnFfiMapRenderer {
    override val backend = MapRenderBackend.METAL
    var layer: CAMetalLayer? = null
    var nextError: Throwable? = null
    var attachments = 0
    val failures = mutableListOf<Throwable>()
    val completed = Channel<Unit>(Channel.UNLIMITED)

    suspend fun awaitFrameOrFailure() {
      withTimeout(5_000L) { completed.receive() }
    }

    override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
      attachments++
    }

    override fun render(
      host: MlnFfiMapHostSession,
      frame: MlnFfiMapFrame,
      captureProjection: Boolean,
    ): MlnFfiFrameResult {
      val error = nextError
      nextError = null
      if (error != null) throw error
      completed.trySend(Unit)
      return MlnFfiFrameResult.Rendered()
    }

    override fun close() = Unit
  }

  private class RecordingRenderer : MlnFfiMapRenderer {
    val events = mutableListOf<String>()
    override val backend = MapRenderBackend.METAL

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
    val EXTENT = MapExtent.fromLogical(32, 32, 1.0)
  }
}
