package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.toLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.compose.util.throwCleanupFailures
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.QuartzCore.CAMetalLayer

/**
 * Drives the shared FFI renderer from a dedicated Apple render thread.
 *
 * MapLibre presents into the view's `CAMetalLayer` itself; `nextDrawable` waits on the layer's
 * drawable queue there, which paces the loop the way `eglSwapBuffers` paces the Android one, so no
 * `CADisplayLink` schedules frames. When [maximumFps] is set, the next frame is posted on a delay
 * instead.
 *
 * Every piece of render state belongs to the render thread; other threads reach it through the
 * queue.
 */
internal class AppleMlnFfiSurfaceController(
  renderer: MlnFfiMapRenderer,
  logger: MapLog?,
  maximumFps: Int? = null,
  private val onFailure: (Throwable) -> Unit,
) : MlnFfiSurfaceRenderLoop<CAMetalLayer>(renderer, logger, "Apple", maximumFps), AutoCloseable {
  override val isClosed: Boolean
    get() = closeRequested

  override val backends = RenderBackendPair(MapRenderBackend.Metal, ComposeRenderBackend.Metal)

  private inner class QueuedAction(val runAtUptimeSeconds: Double, val action: () -> Unit) :
    ScheduledAction {
    override fun cancel() {
      queueCondition.lock()
      try {
        queue.remove(this)
      } finally {
        queueCondition.unlock()
      }
    }
  }

  private val queueCondition = NSCondition()
  private val queue = ArrayDeque<QueuedAction>()
  private var queueClosed = false

  private val completion = CompletableDeferred<Result<Unit>>()
  private val renderThread =
    MlnFfiOwnerThread("maplibre-compose-render") {
      completion.complete(runCatching { runQueue() }.onFailure(::rethrowIfFatal))
    }

  // Render-thread state.
  private val cleanupFailures = mutableListOf<Throwable>()

  /** Set once by the first [close] from any thread, so a second [close] returns early. */
  @Volatile private var closeRequested = false

  init {
    renderThread.start()
  }

  /** Retains the view's layer through queued rendering and its final renderer release. */
  fun surfaceLayoutChanged(layer: CAMetalLayer, extent: MapExtent) {
    post {
      if (closeRequested) return@post
      if (layer !== surface) {
        attachSurface(extent) { layer }
      } else {
        // No generation bump: the extent is part of the session's target key, so a resize
        // retargets the session on its own.
        resizeSurface(extent, reallocates = false)
      }
    }
  }

  /** Acknowledges release of [layer]; a stale loss never detaches its replacement. */
  fun surfaceDestroyed(layer: CAMetalLayer): Deferred<Result<Unit>> {
    val detached = CompletableDeferred<Result<Unit>>()
    val accepted = post {
      val result = runCatching {
        if (surface === layer) detachSurface()
      }
        .onFailure(::rethrowIfFatal)
      detached.complete(result)
      result.getOrThrow()
    }
    return if (accepted) detached else completion
  }

  override fun close() {
    queueCondition.lock()
    try {
      if (queueClosed) return
      closeRequested = true
      queue.addLast(
        QueuedAction(uptimeSeconds()) {
          detachSurface()
          closed = true
        }
      )
      queueClosed = true
      queueCondition.signal()
    } finally {
      queueCondition.unlock()
    }
  }

  suspend fun awaitClosed() = completion.await().getOrThrow()

  override fun isRenderThread(): Boolean = renderThread.isCurrent()

  override fun schedule(delay: Duration, action: () -> Unit): ScheduledAction? {
    queueCondition.lock()
    try {
      if (queueClosed) return null
      val queued = QueuedAction(uptimeSeconds() + delay.toDouble(DurationUnit.SECONDS), action)
      queue.addLast(queued)
      queueCondition.signal()
      return queued
    } finally {
      queueCondition.unlock()
    }
  }

  override fun <T> runAndWait(action: () -> T): T {
    val gate = MlnFfiGate()
    var result: Result<T>? = null
    check(
      post {
        result = runCatching(action)
        gate.open()
      }
    ) {
      "The Apple map render thread is shutting down"
    }
    gate.awaitUntilOpen()
    return checkNotNull(result).getOrThrow()
  }

  @OptIn(BetaInteropApi::class)
  override fun renderTarget(surface: CAMetalLayer, extent: MapExtent, generation: Long) =
    MetalSurfaceTarget(
      device = DefaultMetalDevice,
      layer = NativeHandle(surface.objcPtr().toLong()),
      extent = extent,
      generation = generation,
    )

  override fun onTerminalFailure(message: String, error: Throwable, releaseFailure: Throwable?) {
    releaseFailure?.let(cleanupFailures::add)
    // The presentation owns the map and decides whether to retain it after detachment.
    onFailure(error)
  }

  /** Queues [action] for the render thread, reporting false once the queue has shut down. */
  private fun post(action: () -> Unit): Boolean = schedule(Duration.ZERO, action) != null

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
          remaining.forEach { runAction(it) }
          cleanupFailures.throwCleanupFailures()
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
      // The render thread has no autorelease pool, so each action drains one. Metal and the
      // Objective-C bridge leave temporaries otherwise.
      runAction(action)
    }
  }

  @OptIn(BetaInteropApi::class)
  private fun runAction(action: () -> Unit) {
    try {
      autoreleasepool { action() }
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      cleanupFailures.add(error)
      close()
      runCatching { onFailure(error) }.onFailure(::rethrowIfFatal)
    }
  }

  private companion object {
    /** A null device handle, which the FFI runtime reads as the system default Metal device. */
    val DefaultMetalDevice = NativeHandle(0L)

    fun uptimeSeconds(): Double = NSProcessInfo.processInfo.systemUptime
  }
}
