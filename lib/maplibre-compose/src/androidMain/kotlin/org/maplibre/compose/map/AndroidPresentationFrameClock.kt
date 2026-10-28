package org.maplibre.compose.map

import android.content.Context
import android.os.Build
import android.os.Handler
import android.view.Choreographer
import android.view.WindowManager
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.MonotonicFrameClock

/**
 * Demand-driven clock for the standalone presentation recomposer.
 *
 * [Choreographer] follows the phone display, so composition and [withFrameNanos] animations stay
 * aligned with a 90 Hz or 120 Hz screen. When that display is off, Choreographer stops even if a
 * car Surface is still visible; a Handler tick then keeps style updates and camera animations
 * moving. Map rendering is paced separately by the Surface controller.
 */
internal fun androidPresentationFrameClock(
  context: Context,
  handler: Handler,
): AndroidPresentationFrameClock =
  AndroidPresentationFrameClock(
    Choreographer.getInstance(),
    handler,
    context::displayRefreshHz,
  )

internal class AndroidPresentationFrameClock(
  private val choreographer: Choreographer,
  private val handler: Handler,
  private val refreshHz: () -> Float,
) : MonotonicFrameClock {
  private var lastFrameTimeNanos = 0L
  private val clock = BroadcastFrameClock(::schedule)

  override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = clock.withFrameNanos(onFrame)

  private fun schedule() {
    scheduleFirstWinsFrame(
      postVsync = { onFrame ->
        val callback = Choreographer.FrameCallback(onFrame)
        choreographer.postFrameCallback(callback)
        ({ choreographer.removeFrameCallback(callback) })
      },
      postFallback = { onFrame ->
        val callback = Runnable { onFrame(System.nanoTime()) }
        handler.postDelayed(callback, fallbackFrameDelayMs(refreshHz()))
        ({ handler.removeCallbacks(callback) })
      },
      deliver = { timeNanos ->
        if (!clock.hasAwaiters) return@scheduleFirstWinsFrame
        val time = monotonicFrameTimeNanos(lastFrameTimeNanos, timeNanos)
        lastFrameTimeNanos = time
        clock.sendFrame(time)
      },
    )
  }
}

internal const val DefaultDisplayRefreshHz = 60f
internal const val MinFallbackFrameDelayMs = 16L
internal const val MaxFallbackFrameDelayMs = 48L

internal fun fallbackFrameDelayMs(refreshHz: Float): Long {
  val hz = refreshHz.takeIf { it.isFinite() && it > 1f } ?: DefaultDisplayRefreshHz
  return (2_000f / hz).toLong().coerceIn(MinFallbackFrameDelayMs, MaxFallbackFrameDelayMs)
}

internal fun monotonicFrameTimeNanos(lastFrameTimeNanos: Long, candidateNanos: Long): Long =
  maxOf(candidateNanos, lastFrameTimeNanos + 1)

/**
 * Posts a vsync pulse and a Handler fallback. The first to fire delivers the frame and cancels the
 * other. Both posters must be asynchronous; a synchronous post can miss the sibling cancel.
 */
internal fun scheduleFirstWinsFrame(
  postVsync: (onFrame: (Long) -> Unit) -> () -> Unit,
  postFallback: (onFrame: (Long) -> Unit) -> () -> Unit,
  deliver: (frameTimeNanos: Long) -> Unit,
) {
  var finished = false
  var cancelVsync = {}
  var cancelFallback = {}
  val win: (Long) -> Unit = { time ->
    if (!finished) {
      finished = true
      cancelVsync()
      cancelFallback()
      deliver(time)
    }
  }
  cancelVsync = postVsync(win)
  cancelFallback = postFallback(win)
}

@Suppress("DEPRECATION")
internal fun Context.displayRefreshHz(): Float {
  val display =
    if (Build.VERSION.SDK_INT >= 30) display
    else (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
  val hz = display?.refreshRate ?: return DefaultDisplayRefreshHz
  return if (hz.isFinite() && hz > 1f) hz else DefaultDisplayRefreshHz
}
