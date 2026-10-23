package org.maplibre.compose.mlnffi

import android.graphics.PixelFormat
import android.media.ImageReader
import android.view.Choreographer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidSurfaceFramePacingTest {
  @Test
  fun result_wakes_and_retries_share_one_pass_per_display_frame() {
    val done = CountDownLatch(1)
    val times = mutableListOf<Long>()
    var failure: Throwable? = null
    var frameTime = 0L
    val observer =
      object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
          frameTime = frameTimeNanos
          Choreographer.getInstance().postFrameCallback(this)
        }
      }
    val renderer =
      object : MlnFfiMapRenderer {
        override val backend = MapRenderBackend.OpenGl

        override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
          Choreographer.getInstance().postFrameCallback(observer)
        }

        override fun onSurfaceLost(session: MlnFfiMapHostSession) {
          Choreographer.getInstance().removeFrameCallback(observer)
        }

        override fun render(
          host: MlnFfiMapHostSession,
          frame: MlnFfiMapFrame,
          captureProjection: Boolean,
        ): MlnFfiFrameResult {
          check(frameTime > 0L) { "The renderer ran before the display callback" }
          times += frameTime
          if (times.size == 4) {
            done.countDown()
            return MlnFfiFrameResult.AwaitUpdate
          }
          repeat(8) { host.requestFrame() }
          host.enqueueRenderer { host.requestFrame() }
          return MlnFfiFrameResult.RetryNextFrame
        }

        override fun close() {}
      }
    ImageReader.newInstance(32, 32, PixelFormat.RGBA_8888, 2).use { reader ->
      val controller =
        AndroidMlnFfiSurfaceController(renderer, renderer.backend, logger = null) {
          failure = it
          done.countDown()
        }
      try {
        controller.surfaceCreated(reader.surface, 32, 32, 1.0)
        assertTrue(done.await(10, TimeUnit.SECONDS), "The display did not deliver four frames")
        failure?.let { throw it }
        assertEquals(4, times.size)
        assertEquals(times.size, times.distinct().size, "A result wake bypassed display pacing")
      } finally {
        controller.close()
        reader.surface.release()
      }
    }
  }
}
