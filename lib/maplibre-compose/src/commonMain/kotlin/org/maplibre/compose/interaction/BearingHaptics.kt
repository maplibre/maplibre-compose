package org.maplibre.compose.interaction

import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/** Haptic notches while the user rotates the map, independent of bearing snapping. */
@MapInteractionDsl
public class BearingHapticsBuilder internal constructor() {
  private val notches = mutableListOf<BearingHapticNotch>()

  /**
   * Performs [type] near or across the bearings every [spacing] degrees from [offset], in degrees
   * clockwise from north. Overlapping notches produce one event, using the type of the notch added
   * first. Feedback requires moving away before re-entry and is rate limited during fast rotation.
   * Starting at a notch is silent; crossings between input samples still produce feedback.
   *
   * Throws [IllegalArgumentException] unless [spacing] is positive and finite and [offset] is
   * finite.
   */
  public fun notch(
    spacing: Double,
    offset: Double = 0.0,
    type: HapticFeedbackType = HapticFeedbackType.SegmentTick,
  ) {
    require(spacing.isFinite() && spacing > 0.0) {
      "spacing must be positive and finite, was $spacing"
    }
    require(offset.isFinite()) { "offset must be finite, was $offset" }
    notches += BearingHapticNotch(spacing, offset, type)
  }

  internal fun build(): List<BearingHapticNotch> = notches.toList()
}

internal data class BearingHapticNotch(
  val spacing: Double,
  val offset: Double,
  val type: HapticFeedbackType,
)
