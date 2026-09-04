package org.maplibre.compose.style

import kotlin.time.Duration

/**
 * Returns the platform's animator duration scale: the value of Android's
 * `Settings.Global.ANIMATOR_DURATION_SCALE`, or 1f where the platform has no such setting. A scale
 * of zero means the user asked for no animations.
 */
internal expect fun animatorDurationScale(): Float

/**
 * Returns [this] multiplied by [scale], or [this] unchanged when the scale is 1f. A scale of zero
 * returns [Duration.ZERO], which the camera treats as a jump.
 */
internal fun Duration.scaledBy(scale: Float): Duration {
  requireScale(scale)
  if (scale == 1f) return this
  return this * scale.toDouble()
}

/**
 * Returns [this] with duration and delay multiplied by [scale], or [this] unchanged when the scale
 * is 1f. A scale of zero zeroes both fields, which applies property changes instantly.
 */
internal fun TransitionOptions.scaledBy(scale: Float): TransitionOptions {
  requireScale(scale)
  if (scale == 1f) return this
  return TransitionOptions(duration = duration * scale.toDouble(), delay = delay * scale.toDouble())
}

private fun requireScale(scale: Float) {
  require(scale.isFinite() && scale >= 0f) {
    "Animator duration scale must be finite and not negative: $scale"
  }
}
