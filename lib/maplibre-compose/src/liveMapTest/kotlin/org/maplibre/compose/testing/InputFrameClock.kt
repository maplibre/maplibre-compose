package org.maplibre.compose.testing

import androidx.compose.runtime.MonotonicFrameClock
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * Frames every 16 ms of real time, for input that runs momentum frame by frame outside a
 * composition, which would otherwise supply the clock.
 */
internal object InputFrameClock : MonotonicFrameClock {
  private val start = TimeSource.Monotonic.markNow()

  override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
    delay(16)
    return onFrame(start.elapsedNow().inWholeNanoseconds)
  }
}
