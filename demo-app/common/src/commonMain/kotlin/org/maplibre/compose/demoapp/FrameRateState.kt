package org.maplibre.compose.demoapp

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
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
 * [record] counts one [org.maplibre.compose.map.MapEvent.FrameRendered] event. The counter is
 * deliberately not snapshot state: it advances once per frame, and writing state there would
 * recompose at frame rate.
 *
 * On the browser MapLibre GL JS also draws frames that it schedules itself, so the count can exceed
 * the frames that the map session drives.
 */
@Stable
class FrameRateState {

  @Volatile private var frames = 0L

  /** Frames per second over the last sample window. */
  var framesPerSecond by mutableDoubleStateOf(0.0)
    private set

  /**
   * Called once per rendered frame. One collector calls this, so a plain increment loses nothing.
   */
  fun record() {
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
      lastFrames = nowFrames
      lastMark = nowMark
    }
  }
}
