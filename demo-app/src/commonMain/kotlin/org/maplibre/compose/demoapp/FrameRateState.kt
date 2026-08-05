package org.maplibre.compose.demoapp

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.Volatile
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/** How often the rate is recomputed. Short enough to react, long enough to read. */
private const val SAMPLE_MILLIS = 500L

/**
 * How many frames the map has actually drawn, sampled over time.
 *
 * This counts frames rather than believing the rate the map reports, because the number worth
 * seeing is the one an idle map produces. `onFrame` reports `1 / (time since the previous frame)`,
 * which is only ever the instantaneous rate of a frame that did happen — a map that stops drawing
 * stops reporting, so the last value it gave just sits there looking like a healthy 60. Counting
 * into a window falls to zero instead, which is what an idle map should read.
 *
 * [record] runs on whichever thread presents the map, so the count it keeps is deliberately not
 * Compose state: writing snapshot state once per frame would recompose whatever displays it at
 * frame rate, and on a map already suspected of drawing too much that is a feedback loop, not a
 * measurement. The plain counter is written by that one thread and sampled by [track].
 */
@Stable
class FrameRateState {

  @Volatile private var frames = 0L

  /** Frames per second over the last sample window. */
  var framesPerSecond by mutableDoubleStateOf(0.0)
    private set

  /** Every frame drawn since this state was created. */
  var totalFrames by mutableLongStateOf(0L)
    private set

  /** Called once per rendered frame, from the presenting thread. */
  fun record() {
    // One writer, so a plain increment loses nothing; the read side is a sample, not a total that
    // anything depends on being exact.
    frames++
  }

  /** Samples the counter until cancelled. */
  suspend fun track(): Nothing {
    var lastFrames = frames
    var lastMark = TimeSource.Monotonic.markNow()
    while (true) {
      delay(SAMPLE_MILLIS)
      val nowFrames = frames
      val nowMark = TimeSource.Monotonic.markNow()
      val elapsed = (nowMark - lastMark).toDouble(DurationUnit.SECONDS)
      framesPerSecond = if (elapsed > 0.0) (nowFrames - lastFrames) / elapsed else 0.0
      totalFrames = nowFrames
      lastFrames = nowFrames
      lastMark = nowMark
    }
  }
}
