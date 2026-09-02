package org.maplibre.compose.style

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The style's global transition, which times every paint property change that declares no
 * transition of its own.
 *
 * The defaults match the style spec's `transition` object.
 *
 * @param duration Time allotted for a transition to complete.
 * @param delay Time before a transition begins.
 */
public data class TransitionOptions(
  val duration: Duration = 300.milliseconds,
  val delay: Duration = Duration.ZERO,
)
