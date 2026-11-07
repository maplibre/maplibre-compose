package org.maplibre.compose.mlnffi

import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.FutureTask
import kotlin.time.Duration
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.AndroidFrameTicker
import org.maplibre.compose.map.AndroidVsync
import org.maplibre.compose.map.ChoreographerVsync
import org.maplibre.compose.map.MapExtent
import org.maplibre.nativeffi.FramePacerHandle
import org.maplibre.nativeffi.FramePacerOptions
import org.maplibre.nativeffi.androidFramePacerCreate

/** How an Android surface host has its render session's frames demanded. */
internal enum class AndroidFramePacing {
  /** A MapLibre frame pacer demands frames at the refreshes of the main thread's Choreographer. */
  FramePacer,

  /**
   * The session's redraw wake asks for a frame, which the host demands at its next
   * [AndroidFrameTicker] frame. Frames keep coming while Choreographer stops, such as on an Android
   * Auto car screen while the phone display is off.
   */
  RedrawClock,
}

/**
 * Presents a map on Android [Surface]s through a render session whose graphics context MapLibre
 * creates and drives on its own worker.
 *
 * The session outlives each surface: losing the surface or stopping releases only the session's
 * target, and the next surface replaces it, so the map keeps its renderer and tiles. [pacing]
 * chooses who demands frames. With [AndroidFramePacing.FramePacer], [maximumFps] limits the pacer;
 * with [AndroidFramePacing.RedrawClock], it delays the host's demands.
 *
 * Runs on the Android main thread. Surface callbacks and [close] may also come from other threads,
 * and wait for the main thread.
 */
internal class AndroidMlnFfiSurfaceController(
  renderer: MlnFfiMapRenderer,
  private val backend: MapRenderBackend,
  logger: MapLog?,
  maximumFps: Int? = null,
  private val pacing: AndroidFramePacing = AndroidFramePacing.FramePacer,
  vsync: AndroidVsync = ChoreographerVsync,
  private val onFailure: (Throwable) -> Unit = {},
) :
  MlnFfiSurfaceRenderLoop<Surface>(
    renderer,
    logger,
    "Android",
    maximumFps = maximumFps.takeIf { pacing == AndroidFramePacing.RedrawClock },
    followsFrameClock = true,
    retainsSessionAcrossSurfaces = true,
  ),
  AutoCloseable {
  override val isClosed: Boolean
    get() = closed

  override val backends = RenderBackendPair(backend, ComposeRenderBackend.OpenGl)

  private val main = Handler(Looper.getMainLooper())
  private val ticker = AndroidFrameTicker(main, vsync)

  // Main-thread state.
  private var pacerFps = maximumFps
  private var pacer: FramePacerHandle? = null

  override val frameDemands: MlnFfiFrameDemands
    get() =
      when (pacing) {
        AndroidFramePacing.FramePacer -> MlnFfiFrameDemands.Paced(currentPacer())
        AndroidFramePacing.RedrawClock -> MlnFfiFrameDemands.OnRedraw
      }

  fun surfaceCreated(surface: Surface, width: Int, height: Int, scaleFactor: Double) {
    onMain { attachSurface(MapExtent.fromPhysical(width, height, scaleFactor)) { surface } }
  }

  fun surfaceChanged(width: Int, height: Int, scaleFactor: Double) {
    onMain {
      // The session resizes its window's surface itself.
      resizeSurface(MapExtent.fromPhysical(width, height, scaleFactor), reallocates = false)
    }
  }

  /** Releases the session's target before returning, and keeps the session for the next surface. */
  fun surfaceDestroyed() {
    if (closed) return
    tearDownOnMain {
      try {
        loseSurface()
      } catch (error: Throwable) {
        fail("Failed to destroy the Android map surface", error)
        throw error
      }
    }
  }

  override fun setMaximumFps(maximumFps: Int?) {
    if (pacing == AndroidFramePacing.RedrawClock) return super.setMaximumFps(maximumFps)
    schedule(Duration.ZERO) {
      if (closed || pacerFps == maximumFps) return@schedule
      pacerFps = maximumFps
      // A pacer's rate is fixed. The session stays paced by the old one until the next frame pass
      // gives it the new one.
      pacer?.release()
      pacer = null
      requestFrame()
    }
  }

  override fun close() {
    if (closed) return
    tearDownOnMain {
      if (closed) return@tearDownOnMain
      try {
        detachSurface()
        closed = true
      } catch (error: Throwable) {
        fail("Failed to close the Android map surface", error)
        throw error
      }
      ticker.cancel()
      pacer?.release()
      pacer = null
    }
  }

  override fun isRenderThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

  override fun schedule(delay: Duration, action: () -> Unit): ScheduledAction? {
    if (closed) return null
    val runnable = Runnable { action() }
    val posted =
      if (delay > Duration.ZERO) main.postDelayed(runnable, delay.inWholeMillisRoundedUp())
      else main.post(runnable)
    return if (posted) ScheduledAction { main.removeCallbacks(runnable) } else null
  }

  override fun scheduleFrame(delay: Duration, action: () -> Unit): ScheduledAction? {
    checkRenderThread()
    if (closed) return null
    // A paced session's frame passes only take results, so they need not wait for a display frame.
    if (pacing == AndroidFramePacing.FramePacer) return schedule(delay, action)
    // TODO: pace these sessions with androidFramePacerCreate too, once the pacer keeps demanding
    // frames while Choreographer stops: https://github.com/maplibre/maplibre-native-ffi/issues/1753
    val tick = Runnable { ticker.postFrame { action() } }
    if (delay > Duration.ZERO) main.postDelayed(tick, delay.inWholeMillisRoundedUp())
    else tick.run()
    return ScheduledAction {
      main.removeCallbacks(tick)
      ticker.cancel()
    }
  }

  override fun <T> runAndWait(action: () -> T): T = postToMain(action).get()

  override fun renderTarget(surface: Surface, extent: MapExtent, generation: Long) =
    AndroidSurfaceTarget(surface, backend, extent, generation)

  override fun onTerminalFailure(message: String, error: Throwable, releaseFailure: Throwable?) {
    releaseFailure?.let { logger?.e(it) { "Failed to release the Android map surface" } }
    runCatching { renderer.close() }
      .onFailure { logger?.e(it) { "Failed to close the Android map renderer" } }
    onFailure(IllegalStateException(message, error))
  }

  /** The pacer for this host's sessions, created on the main thread, whose looper it follows. */
  private fun currentPacer(): FramePacerHandle {
    checkRenderThread()
    return pacer
      ?: androidFramePacerCreate(
          FramePacerOptions(maximumFramesPerSecond = pacerFps?.takeIf { it > 0 }?.toUInt()),
        )
        .also { pacer = it }
  }

  private fun onMain(action: () -> Unit) {
    if (isRenderThread()) action() else schedule(Duration.ZERO, action)
  }

  /** Like [runAndWait], but drops a queued frame instead of drawing it while the caller waits. */
  private fun <T> tearDownOnMain(action: () -> T): T {
    val teardown = {
      cancelFrame()
      action()
    }
    if (isRenderThread()) return teardown()
    holdFramesForTeardown()
    return postToMain {
      resumeFramesAfterTeardown()
      teardown()
    }
      .get()
  }

  private fun <T> postToMain(action: () -> T): FutureTask<T> =
    FutureTask(action).also { check(main.post(it)) { "The Android main thread is shutting down" } }
}

private fun Duration.inWholeMillisRoundedUp(): Long = (inWholeNanoseconds + 999_999) / 1_000_000
