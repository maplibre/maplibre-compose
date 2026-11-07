package org.maplibre.compose.map

import android.os.Handler
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.MonotonicFrameClock

/**
 * Clock for the standalone presentation recomposer, which runs style composition and camera
 * animations. Frames come from an [AndroidFrameTicker], which keeps them coming while the phone
 * display is off.
 *
 * Await frames on the thread of [handler].
 */
internal class AndroidPresentationFrameClock(
  handler: Handler,
  vsync: AndroidVsync = ChoreographerVsync,
) : MonotonicFrameClock {
  private val ticker = AndroidFrameTicker(handler, vsync)
  private val clock = BroadcastFrameClock(::requestFrame)

  override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = clock.withFrameNanos(onFrame)

  private fun requestFrame() {
    ticker.postFrame(::sendFrame)
  }

  private fun sendFrame(frameTimeNanos: Long) {
    clock.sendFrame(frameTimeNanos)
  }
}
