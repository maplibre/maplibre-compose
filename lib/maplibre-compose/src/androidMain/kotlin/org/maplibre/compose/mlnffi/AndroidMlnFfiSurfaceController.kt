package org.maplibre.compose.mlnffi

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.util.concurrent.FutureTask
import kotlin.time.Duration
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.MapExtent

/**
 * Drives the shared FFI renderer from a dedicated Android render thread.
 *
 * The thread draws, then the backend presents: `eglSwapBuffers` for OpenGL, the render update for
 * Vulkan. SurfaceView waits on the buffer queue there, matching MapLibre Android's GLSurfaceView
 * loop. TextureView returns from swap without waiting; the next frame is posted when the map
 * requests one, matching MapLibre's TextureView thread.
 *
 * When [maximumFps] is set, the next post is delayed to that interval. Display refresh stays with
 * the window; Compose and the map do not share a SurfaceFlinger vote. `Choreographer.getInstance()`
 * is not the wait; it follows vsync-app, which adaptive refresh holds at 60 Hz for a Surface that
 * is not drawn as a View.
 */
internal class AndroidMlnFfiSurfaceController(
  renderer: MlnFfiMapRenderer,
  private val backend: MapRenderBackend,
  logger: MapLog?,
  maximumFps: Int? = null,
  private val onFailure: (Throwable) -> Unit = {},
) :
  MlnFfiSurfaceRenderLoop<AndroidMapGraphicsContext>(renderer, logger, "Android", maximumFps),
  AutoCloseable {
  override val isClosed: Boolean
    get() = closed

  override val backends = RenderBackendPair(backend, ComposeRenderBackend.OPENGL)

  private val renderThread = HandlerThread("maplibre-compose-render").apply { start() }
  private val renderHandler = Handler(renderThread.looper)

  fun surfaceCreated(surface: Surface, width: Int, height: Int, scaleFactor: Double) {
    renderHandler.post {
      attachSurface(MapExtent.fromPhysical(width, height, scaleFactor)) {
        AndroidMapGraphicsContext.create(backend, surface)
      }
    }
  }

  fun surfaceChanged(width: Int, height: Int, scaleFactor: Double) {
    renderHandler.post {
      resizeSurface(
        MapExtent.fromPhysical(width, height, scaleFactor),
        reallocates = backend == MapRenderBackend.OPENGL,
      )
    }
  }

  fun surfaceDestroyed() {
    if (closed) return
    tearDownOnRenderThread {
      try {
        detachSurface()
      } catch (error: Throwable) {
        fail("Failed to destroy the Android map surface", error)
        throw error
      }
    }
  }

  override fun close() {
    if (closed) return
    tearDownOnRenderThread {
      if (closed) return@tearDownOnRenderThread
      try {
        detachSurface()
        closed = true
      } catch (error: Throwable) {
        fail("Failed to close the Android map surface", error)
        throw error
      }
    }
    if (closed) renderThread.quitSafely()
  }

  override fun isRenderThread(): Boolean = Looper.myLooper() == renderThread.looper

  override fun schedule(delay: Duration, action: () -> Unit): ScheduledAction? {
    val runnable = Runnable { action() }
    val posted =
      if (delay > Duration.ZERO) {
        renderHandler.postDelayed(runnable, (delay.inWholeNanoseconds + 999_999) / 1_000_000)
      } else renderHandler.post(runnable)
    return if (posted) ScheduledAction { renderHandler.removeCallbacks(runnable) } else null
  }

  override fun <T> runAndWait(action: () -> T): T = postToRenderThread(action).get()

  override fun renderTarget(
    surface: AndroidMapGraphicsContext,
    extent: MapExtent,
    generation: Long,
  ): MlnFfiRenderTarget = surface.target(extent, generation)

  override fun releaseSurface(surface: AndroidMapGraphicsContext) {
    surface.close()
  }

  override fun onTerminalFailure(message: String, error: Throwable, releaseFailure: Throwable?) {
    releaseFailure?.let { logger?.e(it) { "Failed to release the Android map surface" } }
    runCatching { renderer.close() }
      .onFailure { logger?.e(it) { "Failed to close the Android map renderer" } }
    onFailure(IllegalStateException(message, error))
  }

  /** Like [runAndWait], but drops a queued frame instead of drawing it while the caller waits. */
  private fun <T> tearDownOnRenderThread(action: () -> T): T {
    val teardown = {
      cancelFrame()
      action()
    }
    if (isRenderThread()) return teardown()
    holdFramesForTeardown()
    return postToRenderThread {
      resumeFramesAfterTeardown()
      teardown()
    }
      .get()
  }

  private fun <T> postToRenderThread(action: () -> T): FutureTask<T> =
    FutureTask(action).also {
      check(renderHandler.post(it)) { "Android map render thread is shutting down" }
    }
}
