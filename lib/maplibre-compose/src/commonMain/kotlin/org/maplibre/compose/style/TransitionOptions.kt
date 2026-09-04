package org.maplibre.compose.style

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Timing for a property change.
 *
 * A layer paint property, a [Light] property, and a [Sky] property each take a transition of their
 * own. A property with no transition of its own uses the style's global transition.
 *
 * The defaults match the style spec's `transition` object.
 *
 * On Android, the system animator duration scale multiplies [duration] and [delay] when the library
 * writes the transition to the engine. A scale of zero applies the change instantly. A loaded style
 * reads the scale once, when it loads; a change to the setting applies to the next style load.
 *
 * @param duration Time allotted for a transition to complete.
 * @param delay Time before a transition begins.
 */
@Immutable
public data class TransitionOptions(
  val duration: Duration = 300.milliseconds,
  val delay: Duration = Duration.ZERO,
) {
  init {
    require(duration.isFinite() && !duration.isNegative()) {
      "Transition duration must be finite and not negative: $duration"
    }
    require(delay.isFinite() && !delay.isNegative()) {
      "Transition delay must be finite and not negative: $delay"
    }
  }
}
