package org.maplibre.compose.demoapp.benchmark

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Average, percentiles, and missed vsyncs from a list of map frame intervals. */
data class FrameTimeStats(
  val frames: Int,
  val avgMs: Double,
  val p50Ms: Double,
  val p95Ms: Double,
  val maxMs: Double,
  val droppedFrames: Int,
  val vsyncMs: Double,
)

/**
 * Summarizes [frameMs] against a vsync interval. An interval that spans two vsyncs counts as one
 * dropped frame; three vsyncs count as two.
 */
fun frameTimeStats(frameMs: List<Double>, vsyncMs: Double): FrameTimeStats {
  if (frameMs.isEmpty()) {
    return FrameTimeStats(
      frames = 0,
      avgMs = 0.0,
      p50Ms = 0.0,
      p95Ms = 0.0,
      maxMs = 0.0,
      droppedFrames = 0,
      vsyncMs = vsyncMs,
    )
  }
  val sorted = frameMs.sorted()
  return FrameTimeStats(
    frames = frameMs.size,
    avgMs = frameMs.average(),
    p50Ms = percentile(sorted, 0.50),
    p95Ms = percentile(sorted, 0.95),
    maxMs = sorted.last(),
    droppedFrames = droppedFrames(frameMs, vsyncMs),
    vsyncMs = vsyncMs,
  )
}

/** Nearest-rank percentile of a sorted list. */
fun percentile(sorted: List<Double>, p: Double): Double {
  if (sorted.isEmpty()) return 0.0
  val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
  return sorted[rank - 1]
}

internal fun droppedFrames(frameMs: List<Double>, vsyncMs: Double): Int {
  if (vsyncMs <= 0.0) return 0
  return frameMs.sumOf { ms -> max(0, (ms / vsyncMs).roundToInt() - 1) }
}
