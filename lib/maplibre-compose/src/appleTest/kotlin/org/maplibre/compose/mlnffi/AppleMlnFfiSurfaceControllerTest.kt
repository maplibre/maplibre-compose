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
      controller.surfaceDestroyed()
      controller.surfaceLayoutChanged(CAMetalLayer(), MapExtent.fromLogical(32, 32, 1.0))
      controller.withRendererAccess {}
      controller.surfaceDestroyed()

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
    attachLayer(controller)
    try {
      controller.close()
      assertTrue(releasing.await(5_000L))
      val closed = async(start = CoroutineStart.UNDISPATCHED) { controller.awaitClosed() }
      assertFalse(closed.isCompleted)
      release.countDown()
      assertTrue(releasedBeforeTimeout.await(), "close blocked until the renderer timed out")
      withTimeout(5_000L) { closed.await() }
      controller.surfaceDestroyed()
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
    attachLayer(controller)
    controller.close()
    assertSame(expected, assertFailsWith<IllegalStateException> { controller.awaitClosed() })
  }

  private fun attachLayer(controller: AppleMlnFfiSurfaceController) {
    controller.surfaceLayoutChanged(CAMetalLayer(), MapExtent.fromLogical(32, 32, 1.0))
    controller.withRendererAccess {}
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
}
