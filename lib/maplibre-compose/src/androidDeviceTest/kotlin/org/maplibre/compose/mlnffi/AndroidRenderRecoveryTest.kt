package org.maplibre.compose.mlnffi

import android.graphics.PixelFormat
import android.media.ImageReader
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.map.MapExtent

class AndroidRenderRecoveryTest {
  @Test
  fun successful_frames_do_not_replenish_recovery_attempts() {
    assertRecoveryLimit(MlnFfiFrameResult.RENDERED)
  }

  @Test
  fun skipped_frames_do_not_replenish_recovery_attempts() {
    assertRecoveryLimit(MlnFfiFrameResult.SKIPPED)
  }

  @Test
  fun consecutive_failures_stop_after_three_rebuilds() {
    withController { controller, renderer ->
      controller.onRenderThread {
        renderer.alwaysFail = true
        controller.requestFrame()
      }
      renderer.awaitFrameOrFailure()
      controller.onRenderThread {
        assertEquals(4, renderer.frames)
        assertEquals(4, renderer.attachments)
        assertEquals(1, renderer.failures.size)
      }
    }
  }

  @Test
  fun surface_recreation_does_not_replenish_recovery_attempts() {
    assertRecoveryLimit(MlnFfiFrameResult.RENDERED, recreateSurface = true)
  }

  @Test
  fun nonrecoverable_failure_is_terminal_without_rebuilding() {
    withController { controller, renderer ->
      val error = IllegalStateException("Unrecoverable test failure")
      controller.onRenderThread { renderer.nextError = error }
      draw(controller, renderer)
      controller.onRenderThread {
        assertEquals(1, renderer.attachments)
        assertEquals(1, renderer.failures.size)
        assertSame(error, renderer.failures.single().cause)
      }
    }
  }

  private fun assertRecoveryLimit(
    result: MlnFfiFrameResult,
    recreateSurface: Boolean = false,
  ) {
    withController { controller, renderer ->
      controller.onRenderThread { renderer.result = result }
      repeat(4) { attempt ->
        controller.onRenderThread {
          renderer.nextError = MlnFfiRecoverableFrameException("Test frame failure", null)
        }
        draw(controller, renderer)
        controller.onRenderThread {
          assertEquals(if (attempt == 3) 1 else 0, renderer.failures.size)
          assertEquals(
            1 + minOf(attempt + 1, 3) + if (recreateSurface) attempt else 0,
            renderer.attachments,
          )
        }
        if (recreateSurface && attempt < 3) {
          controller.onRenderThread { controller.surfaceDestroyed() }
          renderer.attachSurface(controller)
          renderer.awaitFrameOrFailure()
        }
      }
      val frames = controller.onRenderThread { renderer.frames }
      val attachments = controller.onRenderThread { renderer.attachments }
      val resizes = controller.onRenderThread { renderer.resizes }
      controller.onRenderThread { controller.surfaceDestroyed() }
      renderer.attachSurface(controller)
      controller.surfaceChanged(16, 16, 1.0)
      controller.setActive(false)
      controller.setActive(true)
      controller.onRenderThread { controller.requestFrame() }
      controller.onRenderThread {
        assertEquals(frames, renderer.frames)
        assertEquals(attachments, renderer.attachments)
        assertEquals(resizes, renderer.resizes)
        assertEquals(1, renderer.failures.size)
        assertEquals(1, renderer.closes)
      }
    }
  }

  private fun draw(controller: AndroidMlnFfiSurfaceController, renderer: ScriptedRenderer) {
    controller.onRenderThread { controller.requestFrame() }
    renderer.awaitFrameOrFailure()
  }

  private fun <T> AndroidMlnFfiSurfaceController.onRenderThread(action: () -> T): T {
    val task = FutureTask(action)
    assertTrue(enqueueRenderer { task.run() }, "The render thread rejected the test action")
    return task.get(10, TimeUnit.SECONDS)
  }

  private fun withController(action: (AndroidMlnFfiSurfaceController, ScriptedRenderer) -> Unit) {
    ImageReader.newInstance(32, 32, PixelFormat.RGBA_8888, 2).use { reader ->
      val renderer = ScriptedRenderer(reader)
      val controller =
        AndroidMlnFfiSurfaceController(
          renderer,
          renderer.backend,
          logger = null,
          onFailure = {
            renderer.failures.add(it)
            renderer.completed.add(Unit)
          },
        )
      try {
        renderer.attachSurface(controller)
        renderer.awaitFrameOrFailure()
        controller.onRenderThread {
          assertEquals(0, renderer.failures.size, "Surface initialization failed")
          renderer.frames = 0
        }
        action(controller, renderer)
      } finally {
        controller.onRenderThread { controller.close() }
      }
    }
  }

  private class ScriptedRenderer(private val reader: ImageReader) : MlnFfiMapRenderer {
    override val backend = MapRenderBackend.OPENGL
    var nextError: Throwable? = null
    var alwaysFail = false
    var result = MlnFfiFrameResult.RENDERED
    var frames = 0
    var attachments = 0
    var resizes = 0
    var closes = 0
    val failures = mutableListOf<Throwable>()
    val completed = LinkedBlockingQueue<Unit>()

    fun awaitFrameOrFailure() {
      assertNotNull(completed.poll(10, TimeUnit.SECONDS), "No completed frame or terminal failure")
    }

    fun attachSurface(controller: AndroidMlnFfiSurfaceController) {
      controller.surfaceCreated(reader.surface, 32, 32, 1.0)
    }

    override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
      attachments++
    }

    override fun onSurfaceChanged(extent: MapExtent) {
      resizes++
    }

    override fun render(frame: MlnFfiMapFrame): MlnFfiFrameResult {
      frames++
      val error = nextError
      nextError = null
      if (error != null) throw error
      if (alwaysFail) throw MlnFfiRecoverableFrameException("Test frame failure", null)
      completed.add(Unit)
      return result
    }

    override fun close() {
      closes++
    }
  }
}
