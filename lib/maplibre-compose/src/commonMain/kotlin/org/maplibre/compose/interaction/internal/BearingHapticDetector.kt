package org.maplibre.compose.interaction.internal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.interaction.BearingHapticNotch
import org.maplibre.compose.interaction.HapticEmphasis
import org.maplibre.compose.interaction.bearingDelta

/** Engine-applied samples only; one detector belongs to one rotation input session. */
internal class BearingHapticDetector(notches: List<BearingHapticNotch>) {
  private class Notch(val bearing: Double, val emphasis: HapticEmphasis, var armed: Boolean = false)

  private val notches = notches.flatMap { group ->
    group.targets.bearings.map { Notch(it, group.emphasis) }
  }
  private var initialized = false
  private var lastTick: Duration? = null

  fun update(from: Double, to: Double, now: Duration): HapticEmphasis? {
    if (!from.isFinite() || !to.isFinite()) return null
    val travel = bearingDelta(from, to)
    if (travel == 0.0) return null
    var strongest: HapticEmphasis? = null
    for (notch in notches) {
      val distance = bearingDelta(from, notch.bearing)
      if (!initialized) notch.armed = abs(distance) > CaptureDegrees
      val reached =
        distance >= min(0.0, travel) - CaptureDegrees &&
          distance <= max(0.0, travel) + CaptureDegrees
      if (notch.armed && reached) {
        if (strongest == null || notch.emphasis > strongest) strongest = notch.emphasis
        notch.armed = false
      }
      if (abs(bearingDelta(to, notch.bearing)) > RearmDegrees) notch.armed = true
    }
    initialized = true
    // Consume crossings even when suppressed, so they cannot replay as delayed ticks.
    if (strongest == null || lastTick?.let { now - it < 50.milliseconds } == true) return null
    lastTick = now
    return strongest
  }

  private companion object {
    const val CaptureDegrees = 1.0
    const val RearmDegrees = 2.0
  }
}
