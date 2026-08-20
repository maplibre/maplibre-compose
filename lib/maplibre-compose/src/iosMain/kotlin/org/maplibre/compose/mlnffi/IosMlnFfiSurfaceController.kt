package org.maplibre.compose.mlnffi

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.autoreleasepool
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.util.rethrowIfFatal
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * Drives the shared FFI renderer from a dedicated iOS render thread.
 *
 * MapLibre presents into the view's `CAMetalLayer` itself; `nextDrawable` waits on the layer's
 * drawable queue there, which paces the loop the way `eglSwapBuffers` paces the Android one, so no
 * `CADisplayLink` schedules frames. When [maximumFps] is set, the next frame is posted on a delay
 * instead.
 *
 * Every piece of render state below belongs to the render thread; other threads reach it through
 * the queue.
 */
internal class IosMlnFfiSurfaceController(
  private val renderer: MlnFfiMapRenderer,
  private val logger: Logger?,
  maximumFps: Int? = null,
) : MlnFfiMapHostSession, AutoCloseable {
  override val backends = RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)

  private class ScheduledAction(val runAtUptimeSeconds: Double, val action: () -> Unit)

  private val queueCondition = NSCondition()
  private val queue = ArrayDeque<ScheduledAction>()
  private var queueClosed = false

  private val renderThread = MlnFfiOwnerThread("maplibre-compose-render") { runQueue() }

  // Render-thread state.
  private var layerAddress: Long = 0L
  private var maximumFps = maximumFps
  private var extent = MapExtent.Empty
  private var generation = 0L
  private var nextFrameId = 1L
  private var framePosted = false
  private var frameToken = 0L
  private var lastFrameStartUptimeSeconds = 0.0
  private var active = true
  private var closed = false
  private var terminalFailure = false
  private var consecutiveFailures = 0

  /** Set once by the first [close] from any thread, so a second [close] returns early. */
  @Volatile private var closeRequested = false

  init {
    renderThread.start()
  }

  /** Records [maximumFps] for the post delay. */
  fun setMaximumFps(maximumFps: Int?) {
    post { if (!closed) this.maximumFps = maximumFps }
  }

  /**
   * Offers the view's layer and extent to the renderer. [layerAddress] is the `CAMetalLayer`'s
   * address; the view owns the layer and reports each settled [extent] here.
   */
  fun surfaceLayoutChanged(layerAddress: Long, extent: MapExtent) {
    post {
      if (closed) return@post
      if (layerAddress != this.layerAddress) {
        replaceSurfaceOnRenderThread(layerAddress, extent)
      } else if (extent != this.extent) {
        resizeSurfaceOnRenderThread(extent)
      }
    }
  }

  private fun replaceSurfaceOnRenderThread(layerAddress: Long, extent: MapExtent) {
    checkRenderThread()
    surfaceDestroyedOnRenderThread()
    this.layerAddress = layerAddress
    this.extent = extent
    generation++
    try {
      renderer.onSurfaceAvailable(this)
      renderer.onSurfaceChanged(extent)
      requestFrameOnRenderThread()
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      fail("Failed to create the iOS map surface", error)
    }
  }

  private fun resizeSurfaceOnRenderThread(extent: MapExtent) {
    checkRenderThread()
    // No generation bump: the extent is part of the session's target key, so a resize retargets
    // the session on its own.
    this.extent = extent
    try {
      renderer.onSurfaceChanged(extent)
      requestFrameOnRenderThread()
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      fail("Failed to resize the iOS map surface", error)
    }
  }

  /**
   * Drops the render session before the view releases the layer. Blocks so the layer outlives the
   * session that borrows it.
   *
   * UIKit releases interop views in a deferred transaction, which can land after [close]; the close
   * teardown has already dropped the session on the render thread by then.
   */
  fun surfaceDestroyed() {
    if (closeRequested) return
    onRenderThread { surfaceDestroyedOnRenderThread() }
  }

  private fun surfaceDestroyedOnRenderThread() {
    checkRenderThread()
    cancelFrame()
    if (layerAddress == 0L) return
    runCatching { renderer.onSurfaceLost() }
      .onFailure { logger?.e(it) { "Failed to release the iOS map render session" } }
    layerAddress = 0L
    extent = MapExtent.Empty
    consecutiveFailures = 0
  }

  fun setActive(active: Boolean) {
    post { setActiveOnRenderThread(active) }
  }

  private fun setActiveOnRenderThread(active: Boolean) {
    checkRenderThread()
    if (closed || terminalFailure || this.active == active) return
    this.active = active
    if (active) requestFrameOnRenderThread() else cancelFrame()
  }

  override fun requestFrame() {
    if (!renderThread.isCurrent()) {
      post { if (!closed) requestFrameOnRenderThread() }
      return
    }
    requestFrameOnRenderThread()
  }

  private fun requestFrameOnRenderThread() {
    checkRenderThread()
    if (
      closed || terminalFailure || !active || layerAddress == 0L || extent.isEmpty || framePosted
    ) {
      return
    }
    framePosted = true
    val token = ++frameToken
    val intervalSeconds = minFrameIntervalSeconds()
    if (intervalSeconds > 0.0) {
      val at = lastFrameStartUptimeSeconds + intervalSeconds
      val now = uptimeSeconds()
      if (at > now) {
        post(at - now) { renderFrame(token) }
        return
      }
    }
    post { renderFrame(token) }
  }

  private fun renderFrame(token: Long) {
    checkRenderThread()
    if (token != frameToken) return
    framePosted = false
    val currentLayer = layerAddress
    val currentExtent = extent
    if (closed || !active || currentLayer == 0L || currentExtent.isEmpty) return

    val frameId = nextFrameId++
    val target =
      MetalSurfaceTarget(
        device = DEFAULT_METAL_DEVICE,
        layer = NativeHandle(currentLayer),
        extent = currentExtent,
        generation = generation,
      )
    val frame =
      MlnFfiMapFrame(
        frameId = frameId,
        extent = currentExtent,
        target = target,
        presentationTimeNanos = (uptimeSeconds() * NANOS_PER_SECOND).toLong(),
      )

    val frameStartUptimeSeconds = uptimeSeconds()
    try {
      if (renderer.render(frame) == MlnFfiFrameResult.RENDERED) {
        consecutiveFailures = 0
        lastFrameStartUptimeSeconds = frameStartUptimeSeconds
      }
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      consecutiveFailures++
      if (
        error !is MlnFfiRecoverableFrameException || consecutiveFailures > MAX_RECOVERY_ATTEMPTS
      ) {
        fail("iOS map frame $frameId could not recover", error)
        return
      }
      logger?.w(error) {
        "iOS map frame $frameId failed; rebuilding the render session " +
          "(attempt $consecutiveFailures of $MAX_RECOVERY_ATTEMPTS)"
      }

      runCatching { renderer.onSurfaceLost() }
      runCatching { renderer.onSurfaceAvailable(this) }
        .onSuccess { requestFrameOnRenderThread() }
        .onFailure { fail("Failed to recover the iOS map render session", it) }
    }
  }

  override fun <T> withRendererAccess(action: () -> T): T {
    return onRenderThread(action)
  }

  override fun enqueueRenderer(action: () -> Unit): Boolean {
    if (closeRequested) return false
    if (renderThread.isCurrent()) {
      action()
      return true
    }
    return post { action() }
  }

  override fun close() {
    if (closeRequested) return
    closeRequested = true
    onRenderThread {
      if (closed) return@onRenderThread
      surfaceDestroyedOnRenderThread()
      closed = true
    }
    queueCondition.lock()
    try {
      queueClosed = true
      queueCondition.signal()
    } finally {
      queueCondition.unlock()
    }
  }

  private fun cancelFrame() {
    if (!framePosted) return
    framePosted = false
    frameToken++
  }

  private fun minFrameIntervalSeconds(): Double {
    val fps = maximumFps ?: return 0.0
    if (fps <= 0) return 0.0
    return 1.0 / fps
  }

  private fun checkRenderThread() {
    check(renderThread.isCurrent()) { "iOS map rendering must run on its render thread" }
  }

  private fun <T> onRenderThread(action: () -> T): T {
    if (renderThread.isCurrent()) return action()
    val gate = MlnFfiGate()
    var result: Result<T>? = null
    check(
      post {
        result = runCatching(action)
        gate.open()
      }
    ) {
      "The iOS map render thread is shutting down"
    }
    gate.awaitUntilOpen()
    return checkNotNull(result).getOrThrow()
  }

  /** Queues [action] for the render thread, reporting false once the queue has shut down. */
  private fun post(delaySeconds: Double = 0.0, action: () -> Unit): Boolean {
    queueCondition.lock()
    try {
      if (queueClosed) return false
      queue.addLast(ScheduledAction(uptimeSeconds() + delaySeconds, action))
      queueCondition.signal()
      return true
    } finally {
      queueCondition.unlock()
    }
  }

  @OptIn(BetaInteropApi::class)
  private fun runQueue() {
    while (true) {
      queueCondition.lock()
      var action: (() -> Unit)? = null
      while (action == null) {
        if (queueClosed) {
          // A post accepted just before the queue closed must still run, as quitSafely runs the
          // Android queue to empty; each action self-guards on `closed`.
          val remaining = queue.sortedBy { it.runAtUptimeSeconds }.map { it.action }
          queue.clear()
          queueCondition.unlock()
          remaining.forEach { it() }
          return
        }
        // The earliest scheduled action runs first; posting order breaks ties.
        val nextIndex = queue.indices.minByOrNull { queue[it].runAtUptimeSeconds }
        if (nextIndex == null) {
          queueCondition.wait()
        } else {
          val delay = queue[nextIndex].runAtUptimeSeconds - uptimeSeconds()
          if (delay <= 0.0) {
            action = queue.removeAt(nextIndex).action
          } else {
            // A spurious wakeup or an earlier-posted action lands back here and recomputes. The
            // render thread has no autorelease pool, so the deadline's NSDate needs one per wait.
            autoreleasepool {
              queueCondition.waitUntilDate(NSDate.dateWithTimeIntervalSinceNow(delay))
            }
          }
        }
      }
      queueCondition.unlock()
      action()
    }
  }

  private fun fail(message: String, error: Throwable) {
    terminalFailure = true
    cancelFrame()
    logger?.e(error) { message }
    runCatching { renderer.onSurfaceLost() }
    layerAddress = 0L
    extent = MapExtent.Empty
    runCatching { renderer.close() }
      .onFailure { logger?.e(it) { "Failed to close the iOS map renderer" } }
  }

  private companion object {
    const val MAX_RECOVERY_ATTEMPTS = 3
    const val NANOS_PER_SECOND = 1_000_000_000.0

    /** A null device handle, which the FFI runtime reads as the system default Metal device. */
    val DEFAULT_METAL_DEVICE = NativeHandle(0L)

    fun uptimeSeconds(): Double = NSProcessInfo.processInfo.systemUptime
  }
}
