package org.maplibre.compose.mlnffi

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.ui.unit.LayoutDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.MlnFfiMapSession
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingMapCallbacks

/** Exercises the real render thread and EGL teardown at the host boundary. */
class AndroidSurfaceDetachmentTest {
  @Test
  fun surface_destruction_closes_the_native_renderer_before_an_already_requested_lifecycle_close() {
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime = createMapRuntime(MapRuntimeOptions(cacheFile = cacheFile))
    val state = runtime.createMapState(BaseStyle.Empty)
    val nativeSession =
      MlnFfiMapSession(
        lifecycleAuthority = state.lifecycle,
        callbacks = RecordingMapCallbacks(),
        logger = null,
        renderBackend = MapRenderBackend.OPENGL,
        layoutDirection = LayoutDirection.Ltr,
        cacheFile = cacheFile,
      )
    val rendered = CountDownLatch(1)
    val closeRequested = CountDownLatch(1)
    val allowQueuedClose = CountDownLatch(1)
    val interceptClose = AtomicBoolean(false)
    val closeExecutor = Executors.newSingleThreadExecutor()
    val renderer =
      object : MlnFfiMapRenderer by nativeSession {
        override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
          val renderThread = Thread.currentThread()
          nativeSession.onSurfaceAvailable(
            object : MlnFfiMapHostSession by session {
              override fun <T> withRendererAccess(action: () -> T): T {
                if (
                  Thread.currentThread() !== renderThread &&
                    interceptClose.compareAndSet(true, false)
                ) {
                  closeRequested.countDown()
                  check(allowQueuedClose.await(10, TimeUnit.SECONDS)) {
                    "The earlier Surface destruction did not finish"
                  }
                }
                return session.withRendererAccess(action)
              }
            }
          )
        }

        override fun render(frame: MlnFfiMapFrame): MlnFfiFrameResult =
          nativeSession.render(frame).also {
            if (it == MlnFfiFrameResult.RENDERED) rendered.countDown()
          }
      }
    try {
      nativeSession.setBaseStyle(
        BaseStyle.Json(
          """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#336699"}}]}"""
        )
      )
      nativeSession.start()
      withController(renderer) { controller ->
        assertTrue(rendered.await(10, TimeUnit.SECONDS), "The native renderer never drew")
        interceptClose.set(true)
        val closing = closeExecutor.submit {
          state.close()
          runBlocking { withTimeout(10_000) { state.awaitClosed() } }
        }
        try {
          assertTrue(
            closeRequested.await(10, TimeUnit.SECONDS),
            "Lifecycle close never reached the host",
          )
          // The host has not run lifecycle cleanup yet. Surface destruction must still see and
          // close the native handle before EGL releases its thread; otherwise the queued close
          // later uses a freed graphics context.
          controller.surfaceDestroyed()
        } finally {
          allowQueuedClose.countDown()
        }
        closing.get(10, TimeUnit.SECONDS)
        controller.close()
      }
    } finally {
      allowQueuedClose.countDown()
      closeExecutor.shutdownNow()
      runtime.close()
      runBlocking { withTimeout(10_000) { runtime.awaitClosed() } }
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  private fun withController(
    renderer: MlnFfiMapRenderer,
    action: (AndroidMlnFfiSurfaceController) -> Unit,
  ) {
    ImageReader.newInstance(32, 32, PixelFormat.RGBA_8888, 2).use { reader ->
      val consumer = HandlerThread("maplibre-detachment-test-consumer").apply { start() }
      reader.setOnImageAvailableListener(
        { it.acquireLatestImage()?.close() },
        Handler(consumer.looper),
      )
      val surface = reader.surface
      val controller = AndroidMlnFfiSurfaceController(renderer, renderer.backend, logger = null)
      try {
        controller.surfaceCreated(surface, 32, 32, 1.0)
        action(controller)
        assertTrue(surface.isValid, "The controller released its borrowed Surface")
      } finally {
        controller.close()
        surface.release()
        reader.setOnImageAvailableListener(null, null)
        consumer.quitSafely()
        consumer.join()
      }
    }
  }
}
