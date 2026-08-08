package org.maplibre.compose.mlnffi

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import co.touchlab.kermit.Logger
import java.util.concurrent.FutureTask

/** Drives the shared FFI renderer from Android vsync into one embedded EGL surface. */
internal class AndroidMlnFfiSurfaceController(
  private val renderer: MlnFfiMapRenderer,
  private val logger: Logger?,
) : MlnFfiMapHostSession, Choreographer.FrameCallback, AutoCloseable {
  override val backends = RenderBackendPair(MapRenderBackend.OPENGL, ComposeRenderBackend.OPENGL)

  private val mainHandler = Handler(Looper.getMainLooper())
  private val choreographer = Choreographer.getInstance()
  private var graphics: AndroidEglContext? = null
  private var extent = MlnFfiMapExtent.Empty
  private var generation = 0L
  private var nextFrameId = 1L
  private var framePosted = false
  private var active = true
  private var closed = false
  private var terminalFailure = false
  private var consecutiveFailures = 0

  fun surfaceCreated(surface: Surface, width: Int, height: Int, scaleFactor: Double) {
    checkMainThread()
    if (closed) return
    surfaceDestroyed()
    try {
      graphics = AndroidEglContext.create(surface)
      extent = MlnFfiMapExtent.fromPhysical(width, height, scaleFactor)
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
    checkMainThread()
    if (graphics == null || closed) return
    val changed = MlnFfiMapExtent.fromPhysical(width, height, scaleFactor)
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
    checkMainThread()
    cancelFrame()
    if (graphics == null) return
    // The render session names this EGL surface, so it must be closed before EGL destroys it.
    runCatching { renderer.onSurfaceLost() }
      .onFailure { logger?.e(it) { "Failed to release the Android map render session" } }
    runCatching { graphics?.close() }
      .onFailure { logger?.e(it) { "Failed to release the Android EGL context" } }
    graphics = null
    extent = MlnFfiMapExtent.Empty
    consecutiveFailures = 0
  }

  fun setActive(active: Boolean) {
    checkMainThread()
    if (closed || terminalFailure || this.active == active) return
    this.active = active
    if (active) requestFrame() else cancelFrame()
  }

  override fun requestFrame() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post(::requestFrame)
      return
    }
    if (closed || terminalFailure || !active || graphics == null || extent.isEmpty || framePosted) {
      return
    }
    framePosted = true
    choreographer.postFrameCallback(this)
  }

  override fun doFrame(frameTimeNanos: Long) {
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

    try {
      if (renderer.render(frame) == MlnFfiFrameResult.RENDERED) consecutiveFailures = 0
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
    if (Looper.myLooper() == Looper.getMainLooper()) return action()
    val task = FutureTask(action)
    check(mainHandler.post(task)) { "Android main looper is shutting down" }
    return task.get()
  }

  override fun close() {
    checkMainThread()
    if (closed) return
    surfaceDestroyed()
    closed = true
  }

  private fun cancelFrame() {
    if (!framePosted) return
    choreographer.removeFrameCallback(this)
    framePosted = false
  }

  private fun checkMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "Android map surface lifecycle must run on the main thread"
    }
  }

  private fun fail(message: String, error: Throwable) {
    terminalFailure = true
    cancelFrame()
    logger?.e(error) { message }
    runCatching { renderer.onSurfaceLost() }
    runCatching { graphics?.close() }
      .onFailure { logger?.e(it) { "Failed to release the Android EGL context" } }
    graphics = null
    extent = MlnFfiMapExtent.Empty
    runCatching { renderer.close() }
      .onFailure { logger?.e(it) { "Failed to close the Android map renderer" } }
  }

  private companion object {
    const val MAX_RECOVERY_ATTEMPTS = 3
  }
}
