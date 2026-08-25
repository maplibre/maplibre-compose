package org.maplibre.compose.demoapp.benchmark

import kotlin.math.hypot

/** A sample on the pointer or projected-pin timeline, in pixels from the map's top-left. */
data class TimedPoint(val tMs: Double, val xPx: Double, val yPx: Double)

/**
 * How far the map's projection of a pinned point trails the pointer, as pixels and as inferred
 * milliseconds.
 *
 * The time estimate is the along-track gap divided by pointer speed, so it is only taken while the
 * pointer is moving faster than [minSpeedPxPerSec]. A still pointer has no speed to convert a gap
 * into a delay.
 */
data class GestureLatencyStats(
  val samples: Int,
  val medianTrailPx: Double,
  val p95TrailPx: Double,
  val medianLatencyMs: Double,
  val p95LatencyMs: Double,
)

fun estimateGestureLatency(
  pointer: List<TimedPoint>,
  map: List<TimedPoint>,
  minSpeedPxPerSec: Double = 200.0,
): GestureLatencyStats {
  if (pointer.size < 2 || map.isEmpty()) {
    return GestureLatencyStats(0, 0.0, 0.0, 0.0, 0.0)
  }
  val trailPx = ArrayList<Double>()
  val latencyMs = ArrayList<Double>()
  for (sample in map) {
    val pointerAtT = interpolate(pointer, sample.tMs) ?: continue
    val errorX = pointerAtT.xPx - sample.xPx
    val errorY = pointerAtT.yPx - sample.yPx
    trailPx.add(hypot(errorX, errorY))
    val velocity = velocityAt(pointer, sample.tMs) ?: continue
    val speed = hypot(velocity.xPx, velocity.yPx)
    if (speed < minSpeedPxPerSec) continue
    val alongTrack = (errorX * velocity.xPx + errorY * velocity.yPx) / speed
    latencyMs.add(alongTrack / speed * 1000.0)
  }
  val trailSorted = trailPx.sorted()
  val latencySorted = latencyMs.sorted()
  return GestureLatencyStats(
    samples = trailPx.size,
    medianTrailPx = percentile(trailSorted, 0.50),
    p95TrailPx = percentile(trailSorted, 0.95),
    medianLatencyMs = percentile(latencySorted, 0.50),
    p95LatencyMs = percentile(latencySorted, 0.95),
  )
}

internal fun interpolate(samples: List<TimedPoint>, tMs: Double): TimedPoint? {
  if (samples.isEmpty()) return null
  if (tMs <= samples.first().tMs) return samples.first()
  if (tMs >= samples.last().tMs) return samples.last()
  val index = samples.indexOfFirst { it.tMs >= tMs }
  val previous = samples[index - 1]
  val next = samples[index]
  val span = next.tMs - previous.tMs
  if (span <= 0.0) return next
  val u = (tMs - previous.tMs) / span
  return TimedPoint(
    tMs = tMs,
    xPx = previous.xPx + u * (next.xPx - previous.xPx),
    yPx = previous.yPx + u * (next.yPx - previous.yPx),
  )
}

/** Velocity in pixels per second at [tMs], from the neighboring pointer samples. */
internal fun velocityAt(samples: List<TimedPoint>, tMs: Double): TimedPoint? {
  if (samples.size < 2) return null
  val index = samples.indexOfFirst { it.tMs >= tMs }.let { if (it < 0) samples.lastIndex else it }
  val nextIndex = index.coerceIn(1, samples.lastIndex)
  val previous = samples[nextIndex - 1]
  val next = samples[nextIndex]
  val dtSec = (next.tMs - previous.tMs) / 1000.0
  if (dtSec <= 0.0) return null
  return TimedPoint(
    tMs = tMs,
    xPx = (next.xPx - previous.xPx) / dtSec,
    yPx = (next.yPx - previous.yPx) / dtSec,
  )
}
