package org.maplibre.compose.layers

import androidx.compose.animation.core.CubicBezierEasing
import kotlin.math.abs

/** A measurement channel. Retargeting samples the running transition, including its delay. */
internal class IndicatorAnimation(initial: Double) {
  private var start = initial
  var target = initial
    private set

  private var startTime = 0.0
  private var duration = 0.0

  fun value(now: Double): Double {
    if (now < startTime) return start
    if (duration == 0.0) return target
    val t = ((now - startTime) / duration).coerceIn(0.0, 1.0)
    return start + (target - start) * NativeTransitionEasing.transform(t.toFloat())
  }

  fun retarget(next: Double, now: Double, delay: Double, duration: Double, wrap: Boolean = false) {
    val current = value(now)
    val destination = if (wrap) current + ((next - current + 180) % 360 + 360) % 360 - 180 else next
    if (abs(destination - target) < 1e-10) return
    start = current
    target = destination
    startTime = now + delay
    this.duration = duration
  }

  fun finish() {
    start = target
    startTime = 0.0
    duration = 0.0
  }

  fun active(now: Double): Boolean = start != target && now < startTime + duration
}

// MapLibre Native util::DEFAULT_TRANSITION_EASE.
private val NativeTransitionEasing = CubicBezierEasing(0f, 0f, 0.25f, 1f)
