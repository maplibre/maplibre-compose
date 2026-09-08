package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.withFrameNanos
import kotlin.math.pow
import kotlin.time.Duration
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.camera.internal.inputPanBy

/** Continues a drag in screen space, including when a frame covers a large camera displacement. */
internal suspend fun CameraInputTarget.animateFling(
  fling: GestureMath.Fling,
  token: CameraInputToken?,
) {
  animateDecelerating(fling.duration, power = fling.decayPower) { frameFraction ->
    GestureMath.forEachScreenSpaceStep(
      fling.offsetXDp * frameFraction,
      fling.offsetYDp * frameFraction,
    ) { stepX, stepY ->
      inputPanBy(stepX, stepY, gestureToken = token)
    }
  }
}

/**
 * Integrates displacement; velocity falls as `(1 - t)^(power - 1)`. The caller supplies a frame
 * clock in its context.
 */
internal suspend fun animateDecelerating(
  duration: Duration,
  power: Int = GestureMath.TRANSFORM_DECAY_POWER,
  apply: (frameFraction: Double) -> Unit,
) {
  val durationNanos = duration.inWholeNanoseconds.coerceAtLeast(1L)
  val startedAt = withFrameNanos { it }
  var previousEasedProgress = 0.0
  do {
    val now = withFrameNanos { it }
    val progress = ((now - startedAt).toDouble() / durationNanos).coerceIn(0.0, 1.0)
    val easedProgress = 1.0 - (1.0 - progress).pow(power)
    val frameFraction = easedProgress - previousEasedProgress
    if (frameFraction != 0.0) apply(frameFraction)
    previousEasedProgress = easedProgress
  } while (progress < 1.0)
}
