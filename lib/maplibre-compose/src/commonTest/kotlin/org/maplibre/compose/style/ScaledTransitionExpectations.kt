package org.maplibre.compose.style

/**
 * The timing an engine holds for [this]: scaled by the platform's animator duration scale.
 * Assertions that read raw style JSON compare against this.
 */
internal fun TransitionOptions.scaledForEngine(): TransitionOptions =
  scaledBy(animatorDurationScale())

/**
 * The timing a typed getter reports for [this]. The getter divides the animator duration scale back
 * out, so it reports [this] — unless the scale is zero, which zeroed the engine's timing past
 * recovery.
 */
internal fun TransitionOptions.readBackFromEngine(): TransitionOptions =
  if (animatorDurationScale() > 0f) this else scaledForEngine()
