@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.TimeSource
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MapFramePacer
import org.maplibre.compose.util.rethrowIfFatal

/**
 * How many times one surface controller rebuilds its render session after a recoverable frame
 * failure, over the controller's whole life. Rendered frames and surface loss do not replenish the
 * count, so a failure that returns after every rebuild stops instead of flickering forever. The
 * Compose-drawn desktop surface is the exception: its hosts rebuild on purpose after each
 * graphics-device change, so it restarts the count once a rebuilt session presents an image.
 */
internal const val MAX_RENDER_RECOVERY_ATTEMPTS = 3

/**
 * Renders a map from a dedicated render thread into a surface that the platform presents itself,
 * such as an Android `Surface` or an Apple `CAMetalLayer`.
 *
 * Subclasses supply the render thread and the render target for their surface type [S]. All render
 * state belongs to the render thread. [requestFrame], [setActive], [setMaximumFps],
 * [withRendererAccess], and [enqueueRenderer] may be called from any thread.
 *
 * A failure that is not recoverable, or a recoverable one after [MAX_RENDER_RECOVERY_ATTEMPTS]
 * rebuilds, is terminal: the controller releases its surface, reports the failure through
 * [onTerminalFailure], and ignores later surfaces, resizes, and activation changes.
 */
internal abstract class MlnFfiSurfaceRenderLoop<S : Any>(
  protected val renderer: MlnFfiMapRenderer,
  protected val logger: MapLog?,
  /** Names the platform in log and failure messages. */
  private val platformName: String,
  maximumFps: Int?,
) : MlnFfiMapHostSession {
  /** A queued render-thread action that can be removed before it starts. */
  fun interface ScheduledAction {
    /** Removes the action if it has not started. Safe to call from any thread. */
    fun cancel()
  }

  /** Whether the calling thread is the render thread. */
  protected abstract fun isRenderThread(): Boolean

  /**
   * Queues [action] for the render thread to run after [delay]. Returns null once the thread
   * accepts no more work. Safe to call from any thread.
   */
  protected abstract fun schedule(delay: Duration, action: () -> Unit): ScheduledAction?

  /** Runs [action] on the render thread and waits for its result. Not called on that thread. */
  protected abstract fun <T> runAndWait(action: () -> T): T

  /** The render target for a frame into [surface] at [extent]. */
  protected abstract fun renderTarget(
    surface: S,
    extent: MapExtent,
    generation: Long,
  ): MlnFfiRenderTarget

  /** Frees the platform resources of [surface] after the renderer let go of it. May throw. */
  protected open fun releaseSurface(surface: S) {}

  /**
   * Reports a terminal failure. The surface is already released, unless [releaseFailure] says why
   * it could not be.
   */
  protected abstract fun onTerminalFailure(
    message: String,
    error: Throwable,
    releaseFailure: Throwable?,
  )

  // Render-thread state.
  protected var surface: S? = null
    private set

  private var extent = MapExtent.Empty
  private var generation = 0L
  private var nextFrameId = 1L
  private var scheduledFrame: ScheduledAction? = null
  /** Teardowns queued from other threads; frames that run before them draw nothing. */
  private val queuedTeardowns = AtomicInt(0)
  private val pacer = MapFramePacer()
  private var maximumFps = maximumFps
  private var active = true
  /** Set by the subclass once its teardown released the surface. */
  @Volatile protected var closed = false
  private var terminalFailure = false
  private var recoveryAttempts = 0

  /** Records [maximumFps] for the delay before the next frame. */
  fun setMaximumFps(maximumFps: Int?) {
    schedule(Duration.ZERO) {
      checkRenderThread()
      if (closed || this.maximumFps == maximumFps) return@schedule
      this.maximumFps = maximumFps
      if (scheduledFrame != null) {
        cancelFrame()
        requestFrameOnRenderThread()
      }
    }
  }

  fun setActive(active: Boolean) {
    schedule(Duration.ZERO) {
      checkRenderThread()
      if (closed || terminalFailure || this.active == active) return@schedule
      this.active = active
      if (active) requestFrameOnRenderThread() else cancelFrame()
    }
  }

  override fun requestFrame() {
    if (isRenderThread()) {
      requestFrameOnRenderThread()
    } else {
      schedule(Duration.ZERO) { if (!closed) requestFrameOnRenderThread() }
    }
  }

  override fun <T> withRendererAccess(action: () -> T): T =
    if (isRenderThread()) action() else runAndWait(action)

  override fun enqueueRenderer(action: () -> Unit): Boolean {
    if (isClosed) return false
    if (isRenderThread()) {
      action()
      return true
    }
    return schedule(Duration.ZERO, action) != null
  }

  /** Replaces the surface with the one [create] returns. Ignored once closed or failed. */
  protected fun attachSurface(extent: MapExtent, create: () -> S) {
    checkRenderThread()
    if (closed || terminalFailure) return
    try {
      detachSurface()
      surface = create()
      this.extent = extent
      generation++
      renderer.onSurfaceAvailable(this)
      renderer.onSurfaceChanged(extent)
      requestFrameOnRenderThread()
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      fail("Failed to create the $platformName map surface", error)
    }
  }

  /**
   * Applies a new [extent] to the current surface. When the surface [reallocates] its buffers on
   * resize, the next frame gets a new target generation. Ignored without a surface or once failed.
   */
  protected fun resizeSurface(extent: MapExtent, reallocates: Boolean) {
    checkRenderThread()
    if (surface == null || closed || terminalFailure || extent == this.extent) return
    this.extent = extent
    if (reallocates) generation++
    try {
      renderer.onSurfaceChanged(extent)
      requestFrameOnRenderThread()
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      fail("Failed to resize the $platformName map surface", error)
    }
  }

  /**
   * Makes the renderer let go of the surface, then releases it. Throws when either step fails, and
   * then keeps the surface so a later call can retry.
   */
  protected fun detachSurface() {
    checkRenderThread()
    cancelFrame()
    val current = surface ?: return
    // The render session names this surface, so it must be closed before the surface is freed.
    renderer.onSurfaceLost(this)
    releaseSurface(current)
    surface = null
    extent = MapExtent.Empty
  }

  /** Drops the queued frame. */
  protected fun cancelFrame() {
    scheduledFrame?.cancel()
    scheduledFrame = null
  }

  /**
   * Stops frames from drawing until the matching [resumeFramesAfterTeardown]. A caller on another
   * thread calls this before it queues a teardown, so the render thread does not draw one more
   * frame before reaching it. Safe to call from any thread.
   */
  protected fun holdFramesForTeardown() {
    queuedTeardowns.fetchAndAdd(1)
  }

  /** Ends one [holdFramesForTeardown]. Called by the queued teardown on the render thread. */
  protected fun resumeFramesAfterTeardown() {
    queuedTeardowns.fetchAndAdd(-1)
  }

  protected fun checkRenderThread() {
    check(isRenderThread()) { "$platformName map rendering must run on its render thread" }
  }

  protected fun fail(message: String, error: Throwable) {
    rethrowIfFatal(error)
    terminalFailure = true
    cancelFrame()
    logger?.e(error) { message }
    val releaseFailure =
      try {
        detachSurface()
        null
      } catch (release: Throwable) {
        rethrowIfFatal(release)
        release
      }
    onTerminalFailure(message, error, releaseFailure)
  }

  private fun requestFrameOnRenderThread() {
    checkRenderThread()
    if (
      closed ||
        terminalFailure ||
        !active ||
        surface == null ||
        extent.isEmpty ||
        scheduledFrame != null
    ) {
      return
    }
    scheduledFrame = schedule(pacer.remaining(maximumFps)) { renderFrame() }
  }

  private fun renderFrame() {
    scheduledFrame = null
    val currentSurface = surface
    val currentExtent = extent
    if (
      isClosed ||
        closed ||
        terminalFailure ||
        !active ||
        currentSurface == null ||
        currentExtent.isEmpty ||
        queuedTeardowns.load() > 0
    ) {
      return
    }

    if (pacer.remaining(maximumFps) > Duration.ZERO) {
      requestFrameOnRenderThread()
      return
    }

    val frameId = nextFrameId++
    val target = renderTarget(currentSurface, currentExtent, generation)
    val frame = MlnFfiMapFrame(target)

    val start = TimeSource.Monotonic.markNow()
    try {
      when (renderer.render(this, frame)) {
        is MlnFfiFrameResult.Rendered -> pacer.rendered(start)
        MlnFfiFrameResult.RetryNextFrame -> requestFrameOnRenderThread()
        MlnFfiFrameResult.AwaitUpdate -> Unit
      }
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      if (
        error !is MlnFfiRecoverableFrameException ||
          recoveryAttempts >= MAX_RENDER_RECOVERY_ATTEMPTS
      ) {
        fail("$platformName map frame $frameId could not recover", error)
        return
      }
      recoveryAttempts++
      logger?.w(error) {
        "$platformName map frame $frameId failed; rebuilding the render session " +
          "(attempt $recoveryAttempts of $MAX_RENDER_RECOVERY_ATTEMPTS)"
      }

      // A lost context invalidates the session but not the surface or the map runtime.
      try {
        renderer.onSurfaceLost(this)
        renderer.onSurfaceAvailable(this)
        requestFrameOnRenderThread()
      } catch (error: Throwable) {
        rethrowIfFatal(error)
        fail("Failed to recover the $platformName map render session", error)
      }
    }
  }
}
