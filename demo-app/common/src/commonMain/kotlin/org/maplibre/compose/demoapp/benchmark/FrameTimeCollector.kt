package org.maplibre.compose.demoapp.benchmark

import kotlin.concurrent.Volatile

/**
 * Inter-frame intervals from the map's [org.maplibre.compose.map.MaplibreMap] `onFrame` callback
 * and from Compose vsync. [recordMapFps] runs on the presenting thread; [stop] is called from the
 * scenario coroutine after recording ends.
 */
class FrameTimeCollector(private val capacity: Int = 16_384) {
  private val mapMs = DoubleArray(capacity)
  private val composeMs = DoubleArray(capacity)

  @Volatile private var mapCount = 0

  @Volatile private var composeCount = 0

  @Volatile
  var recording: Boolean = false
    private set

  fun start() {
    mapCount = 0
    composeCount = 0
    recording = true
  }

  fun recordMapFps(framesPerSecond: Double) {
    if (!recording || framesPerSecond <= 0.0) return
    val index = mapCount
    if (index >= mapMs.size) return
    mapMs[index] = 1000.0 / framesPerSecond
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
