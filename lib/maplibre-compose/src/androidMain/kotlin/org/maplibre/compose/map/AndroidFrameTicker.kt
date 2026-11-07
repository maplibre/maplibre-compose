package org.maplibre.compose.map

import android.os.Handler
import android.view.Choreographer

/** The display's frame callbacks. A test can stand in for a display that stopped. */
internal interface AndroidVsync {
  fun postFrameCallback(callback: Choreographer.FrameCallback)

  fun removeFrameCallback(callback: Choreographer.FrameCallback)
}

/** The calling thread's [Choreographer]. */
internal object ChoreographerVsync : AndroidVsync {
  override fun postFrameCallback(callback: Choreographer.FrameCallback) {
    Choreographer.getInstance().postFrameCallback(callback)
  }

  override fun removeFrameCallback(callback: Choreographer.FrameCallback) {
    Choreographer.getInstance().removeFrameCallback(callback)
  }
}

/**
 * Calls back once per requested frame, on the thread of [handler].
 *
 * Frames follow [vsync], so they match a 90 Hz or 120 Hz phone display. Choreographer can stop
 * while the phone display is off, even while a car Surface is visible, so a [handler] timer calls
 * back when vsync is late. After the timer calls back, it runs every 16 ms until vsync arrives
 * first again.
 *
 * Use it on the thread of [handler].
 */
internal class AndroidFrameTicker(
  private val handler: Handler,
  private val vsync: AndroidVsync = ChoreographerVsync,
) {
  private val vsyncCallback = Choreographer.FrameCallback { send(it, fromVsync = true) }
  private val timerCallback = Runnable { send(System.nanoTime(), fromVsync = false) }
  private var pending: ((Long) -> Unit)? = null
  private var vsyncStopped = false
  private var lastFrameTimeNanos = Long.MIN_VALUE

  /**
   * Calls [onFrame] at the next frame with the frame's time in nanoseconds, which only increases.
   * Replaces a callback that is still pending.
   */
  fun postFrame(onFrame: (frameTimeNanos: Long) -> Unit) {
    val requested = pending != null
    pending = onFrame
    if (requested) return
    vsync.postFrameCallback(vsyncCallback)
    handler.postDelayed(timerCallback, if (vsyncStopped) TimerFrameMillis else VsyncTimeoutMillis)
  }

  /** Drops the pending callback. */
  fun cancel() {
    pending = null
    vsync.removeFrameCallback(vsyncCallback)
    handler.removeCallbacks(timerCallback)
  }

  private fun send(timeNanos: Long, fromVsync: Boolean) {
    val onFrame = pending ?: return
    cancel()
    vsyncStopped = !fromVsync
    // A vsync timestamp can precede the time of a timer frame sent just before it.
    lastFrameTimeNanos = maxOf(timeNanos, lastFrameTimeNanos + 1)
    onFrame(lastFrameTimeNanos)
  }
}

private const val TimerFrameMillis = 16L

// Longer than a vsync period at the lowest refresh rate a phone display runs while it is on.
private const val VsyncTimeoutMillis = 50L
