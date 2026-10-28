package org.maplibre.compose.map

import android.os.Handler
import android.view.Choreographer
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.MonotonicFrameClock

/**
 * Clock for the standalone presentation recomposer, which runs style composition and camera
 * animations. The Surface controller paces map rendering separately.
 *
 * Frames follow [Choreographer], so animations match a 90 Hz or 120 Hz phone display. Choreographer
 * can stop while the phone display is off, even while a car Surface is visible, so a [handler]
 * timer sends the frame when vsync is late. After the timer sends a frame, it runs every 16 ms
 * until vsync arrives first again.
 *
 * Await frames on the thread of [handler].
 */
internal class AndroidPresentationFrameClock(private val handler: Handler) : MonotonicFrameClock {
  private val clock = BroadcastFrameClock(::requestFrame)
  private val vsyncCallback = Choreographer.FrameCallback { sendFrame(it, fromVsync = true) }
  private val timerCallback = Runnable { sendFrame(System.nanoTime(), fromVsync = false) }
  private var requested = false
  private var vsyncStopped = false
  private var lastFrameTimeNanos = Long.MIN_VALUE

  override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = clock.withFrameNanos(onFrame)

  private fun requestFrame() {
    if (requested) return
    requested = true
    Choreographer.getInstance().postFrameCallback(vsyncCallback)
    handler.postDelayed(timerCallback, if (vsyncStopped) TimerFrameMillis else VsyncTimeoutMillis)
  }

  private fun sendFrame(timeNanos: Long, fromVsync: Boolean) {
    requested = false
    Choreographer.getInstance().removeFrameCallback(vsyncCallback)
    handler.removeCallbacks(timerCallback)
    vsyncStopped = !fromVsync
    // A vsync timestamp can precede the time of a timer frame sent just before it.
    lastFrameTimeNanos = maxOf(timeNanos, lastFrameTimeNanos + 1)
    clock.sendFrame(lastFrameTimeNanos)
  }
}

private const val TimerFrameMillis = 16L

// Longer than a vsync period at the lowest refresh rate a phone display runs while it is on.
private const val VsyncTimeoutMillis = 50L
