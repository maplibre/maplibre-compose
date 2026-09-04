package org.maplibre.compose.style

import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Returns the platform's animator duration scale: the value of Android's
 * `Settings.Global.ANIMATOR_DURATION_SCALE`, or 1f where the platform has no such setting. A scale
 * of zero means the user asked for no animations.
 *
 * A camera animation reads the scale when it starts. A style binding reads it once, when the style
 * loads, and applies that value to every transition it writes and reads: see
 * [StyleBinding.animatorDurationScale].
 */
internal expect fun systemAnimatorDurationScale(): Float

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

/**
 * The inverse of [scaledBy], for reporting engine-held timing through the logical API: a typed
 * getter returns the timing the caller declared, not the timing the engine runs. A scale of zero
 * zeroed the engine's timing past recovery, so the engine's value is reported unchanged.
 */
internal fun TransitionOptions.unscaledBy(scale: Float): TransitionOptions {
  requireScale(scale)
  if (scale == 1f || scale == 0f) return this
  return TransitionOptions(duration = duration / scale.toDouble(), delay = delay / scale.toDouble())
}

/**
 * Returns [this] with the `duration` and `delay` of every `<property>-transition` object multiplied
 * by [scale], for the paint object of a layer and for the light and sky objects. Other keys, and a
 * transition object without a field, pass through unchanged.
 */
internal fun JsonObject.withScaledTransitions(scale: Float): JsonObject {
  requireScale(scale)
  if (scale == 1f) return this
  return JsonObject(
    mapValues { (name, value) ->
      if (name.endsWith(TRANSITION_SUFFIX) && value is JsonObject) value.scaledTransition(scale)
      else value
    }
  )
}

private fun JsonObject.scaledTransition(scale: Float): JsonObject =
  JsonObject(
    mapValues { (field, value) ->
      val millis = (value as? JsonPrimitive)?.doubleOrNull
      if (millis != null && (field == "duration" || field == "delay")) {
        JsonPrimitive(millis * scale)
      } else {
        value
      }
    }
  )

private fun requireScale(scale: Float) {
  require(scale.isFinite() && scale >= 0f) {
    "Animator duration scale must be finite and not negative: $scale"
  }
}
