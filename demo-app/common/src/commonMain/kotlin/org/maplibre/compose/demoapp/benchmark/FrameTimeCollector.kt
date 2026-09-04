package org.maplibre.compose.demoapp.benchmark

import kotlin.concurrent.Volatile
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Inter-frame intervals from the map's [org.maplibre.compose.map.MapEvent.FrameRendered] events and
 * from Compose vsync. One collector calls [recordMapFrame], and [stop] is called from the scenario
 * coroutine after recording ends.
 *
 * A map interval is the difference between two marks that the caller supplies.
 */
class FrameTimeCollector(private val capacity: Int = 16_384) {
  private val mapMs = DoubleArray(capacity)
  private val composeMs = DoubleArray(capacity)

  @Volatile private var mapCount = 0

  @Volatile private var composeCount = 0

  @Volatile private var lastMapMark: TimeSource.Monotonic.ValueTimeMark? = null

  @Volatile
  var recording: Boolean = false
    private set

  fun start() {
    mapCount = 0
    composeCount = 0
    lastMapMark = null
    recording = true
  }

  /** Records the interval since the previous frame. The first frame after [start] sets the mark. */
  fun recordMapFrame(now: TimeSource.Monotonic.ValueTimeMark) {
    if (!recording) return
    val previous = lastMapMark
    lastMapMark = now
    if (previous == null) return
    val index = mapCount
    if (index >= mapMs.size) return
    mapMs[index] = (now - previous).toDouble(DurationUnit.MILLISECONDS)
    mapCount = index + 1
  }

  fun recordComposeFrameMs(frameMs: Double) {
    if (!recording || frameMs <= 0.0) return
    val index = composeCount
    if (index >= composeMs.size) return
    composeMs[index] = frameMs
    composeCount = index + 1
  }

  fun stop(): FrameTimeStats {
    recording = false
    val mapFrames = mapMs.copyOf(mapCount).toList()
    val composeFrames = composeMs.copyOf(composeCount).toList()
    val vsyncMs =
      if (composeFrames.isEmpty()) 1000.0 / 60.0 else percentile(composeFrames.sorted(), 0.50)
    return frameTimeStats(mapFrames, vsyncMs)
  }
}
