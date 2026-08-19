package org.maplibre.compose.mlnffi

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import co.touchlab.kermit.Logger
import java.util.concurrent.FutureTask
import org.maplibre.compose.map.MapExtent

/**
 * Drives the shared FFI renderer from a dedicated Android render thread.
 *
 * The thread draws, then `eglSwapBuffers` presents. SurfaceView waits on the buffer queue there,
 * matching MapLibre Android's GLSurfaceView loop. TextureView returns from swap without waiting;
 * the next frame is posted when the map requests one, matching MapLibre's TextureView thread.
 *
 * When [maximumFps] is set, the next post is delayed to that interval. Display refresh stays with
 * the window; Compose and the map do not share a SurfaceFlinger vote. `Choreographer.getInstance()`
 * is not the wait; it follows vsync-app, which adaptive refresh holds at 60 Hz for a Surface that
 * is not drawn as a View.
 */
internal class AndroidMlnFfiSurfaceController(
  private val renderer: MlnFfiMapRenderer,
  private val logger: Logger?,
  maximumFps: Int? = null,
) : MlnFfiMapHostSession, AutoCloseable {
  override val backends = RenderBackendPair(MapRenderBackend.OPENGL, ComposeRenderBackend.OPENGL)

  private val renderThread = HandlerThread("maplibre-compose-render").apply { start() }
  private val renderHandler = Handler(renderThread.looper)
  private val renderFrame = Runnable { renderFrame(System.nanoTime()) }
  private var graphics: AndroidEglContext? = null
  private var maximumFps = maximumFps
  private var extent = MapExtent.Empty
  private var generation = 0L
  private var nextFrameId = 1L
  private var framePosted = false
  private var lastFrameStartUptimeMs = 0L
  private var active = true
  @Volatile private var closed = false
  private var terminalFailure = false
  private var consecutiveFailures = 0

  /** Records [maximumFps] for the post delay. */
  fun setMaximumFps(maximumFps: Int?) {
    renderHandler.post { setMaximumFpsOnRenderThread(maximumFps) }
  }

  private fun setMaximumFpsOnRenderThread(maximumFps: Int?) {
    checkRenderThread()
    if (closed) return
    this.maximumFps = maximumFps
  }

  fun surfaceCreated(surface: Surface, width: Int, height: Int, scaleFactor: Double) {
    renderHandler.post { surfaceCreatedOnRenderThread(surface, width, height, scaleFactor) }
  }

  private fun surfaceCreatedOnRenderThread(
    surface: Surface,
    width: Int,
    height: Int,
    scaleFactor: Double,
  ) {
    checkRenderThread()
    if (closed) return
    surfaceDestroyedOnRenderThread()
    try {
      graphics = AndroidEglContext.create(surface)
      extent = MapExtent.fromPhysical(width, height, scaleFactor)
      generation++
      renderer.onSurfaceAvailable(this)
      renderer.onSurfaceChanged(extent)
      requestFrame()
    } catch (error: Throwable) {
      if (error is VirtualMachineError) throw error
      fail("Failed to create the Android map surface", error)
    }
  }

  fun surfaceChanged(width: Int, height: Int, scaleFactor: Double) {
    renderHandler.post { surfaceChangedOnRenderThread(width, height, scaleFactor) }
  }

  private fun surfaceChangedOnRenderThread(width: Int, height: Int, scaleFactor: Double) {
    checkRenderThread()
    if (graphics == null || closed) return
    val changed = MapExtent.fromPhysical(width, height, scaleFactor)
    if (changed == extent) return
    extent = changed
    generation++
    try {
      renderer.onSurfaceChanged(changed)
      requestFrame()
    } catch (error: Throwable) {
      if (error is VirtualMachineError) throw error
      fail("Failed to resize the Android map surface", error)
    }
  }

  fun surfaceDestroyed() {
    onRenderThread { surfaceDestroyedOnRenderThread() }
  }

  private fun surfaceDestroyedOnRenderThread() {
    checkRenderThread()
    cancelFrame()
    if (graphics == null) return
    // The render session names this EGL surface, so it must be closed before EGL destroys it.
    runCatching { renderer.onSurfaceLost() }
      .onFailure { logger?.e(it) { "Failed to release the Android map render session" } }
    runCatching { graphics?.close() }
      .onFailure { logger?.e(it) { "Failed to release the Android EGL context" } }
    graphics = null
    extent = MapExtent.Empty
    consecutiveFailures = 0
  }

  fun setActive(active: Boolean) {
    renderHandler.post { setActiveOnRenderThread(active) }
  }

  private fun setActiveOnRenderThread(active: Boolean) {
    checkRenderThread()
    if (closed || terminalFailure || this.active == active) return
    this.active = active
    if (active) requestFrame() else cancelFrame()
  }

  override fun requestFrame() {
    if (Looper.myLooper() != renderThread.looper) {
      renderHandler.post(::requestFrame)
      return
    }
    if (closed || terminalFailure || !active || graphics == null || extent.isEmpty || framePosted) {
      return
    }
    framePosted = true
    val delayMs = minFrameIntervalMs()
    if (delayMs > 0L) {
      val at = lastFrameStartUptimeMs + delayMs
      val now = SystemClock.uptimeMillis()
      if (at > now) {
        renderHandler.postAtTime(renderFrame, at)
        return
      }
    }
    renderHandler.post(renderFrame)
  }

  private fun renderFrame(frameTimeNanos: Long) {
    framePosted = false
    val currentGraphics = graphics
    val currentExtent = extent
    if (closed || !active || currentGraphics == null || currentExtent.isEmpty) return

    val frameId = nextFrameId++
    val target =
      OpenGlSurfaceTarget(
        context = currentGraphics.contextHandles,
        surface = currentGraphics.surfaceHandle,
        extent = currentExtent,
        generation = generation,
      )
    val frame = MlnFfiMapFrame(frameId, currentExtent, target, frameTimeNanos)

    val frameStartUptimeMs = SystemClock.uptimeMillis()
    try {
      if (renderer.render(frame) == MlnFfiFrameResult.RENDERED) {
        consecutiveFailures = 0
        lastFrameStartUptimeMs = frameStartUptimeMs
      }
    } catch (error: Throwable) {
      if (error is VirtualMachineError) throw error
      consecutiveFailures++
      if (
        error !is MlnFfiRecoverableFrameException || consecutiveFailures > MAX_RECOVERY_ATTEMPTS
      ) {
        fail("Android map frame $frameId could not recover", error)
        return
      }
      logger?.w(error) {
        "Android map frame $frameId failed; rebuilding the render session " +
          "(attempt $consecutiveFailures of $MAX_RECOVERY_ATTEMPTS)"
      }

      // A lost context invalidates the session but not the Android surface or the map runtime.
      runCatching { renderer.onSurfaceLost() }
      runCatching { renderer.onSurfaceAvailable(this) }
        .onSuccess { requestFrame() }
        .onFailure { fail("Failed to recover the Android map render session", it) }
    }
  }

  override fun <T> withRendererAccess(action: () -> T): T {
    return onRenderThread(action)
  }

  override fun enqueueRenderer(action: () -> Unit): Boolean {
    if (closed) return false
    if (Looper.myLooper() == renderThread.looper) {
      action()
      return true
    }
    return renderHandler.post(action)
  }

  override fun close() {
    if (closed) return
    onRenderThread {
      if (closed) return@onRenderThread
      surfaceDestroyedOnRenderThread()
      closed = true
    }
    renderThread.quitSafely()
  }

  private fun cancelFrame() {
    if (!framePosted) return
    renderHandler.removeCallbacks(renderFrame)
    framePosted = false
  }

  private fun minFrameIntervalMs(): Long {
    val fps = maximumFps ?: return 0L
    if (fps <= 0) return 0L
    return (1000.0 / fps).toLong().coerceAtLeast(1L)
  }

  private fun checkRenderThread() {
    check(Looper.myLooper() == renderThread.looper) {
      "Android map rendering must run on its render thread"
    }
  }

  private fun <T> onRenderThread(action: () -> T): T {
    if (Looper.myLooper() == renderThread.looper) return action()
    val task = FutureTask(action)
    check(renderHandler.post(task)) { "Android map render thread is shutting down" }
    return task.get()
  }

  private fun fail(message: String, error: Throwable) {
    terminalFailure = true
    cancelFrame()
    logger?.e(error) { message }
    runCatching { renderer.onSurfaceLost() }
    runCatching { graphics?.close() }
      .onFailure { logger?.e(it) { "Failed to release the Android EGL context" } }
    graphics = null
    extent = MapExtent.Empty
    runCatching { renderer.close() }
      .onFailure { logger?.e(it) { "Failed to close the Android map renderer" } }
  }

  private companion object {
    const val MAX_RECOVERY_ATTEMPTS = 3
  }
}
