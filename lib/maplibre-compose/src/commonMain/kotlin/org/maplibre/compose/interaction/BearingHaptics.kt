package org.maplibre.compose.interaction

import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/** Haptic notches during pointer rotation, independent of bearing snapping. */
@MapInteractionDsl
public class BearingHapticsBuilder internal constructor() {
  private val notches = mutableListOf<BearingHapticNotch>()

  /**
   * Performs [type] near or across [targets]. Overlapping notches produce one event, using the type
   * of the notch added first. Feedback requires moving away before re-entry and is rate limited
   * during fast rotation. Starting at a notch is silent; crossings between input samples still
   * produce feedback.
   */
  public fun notch(
    targets: BearingTargets,
    type: HapticFeedbackType = HapticFeedbackType.SegmentTick,
  ) {
    notches += BearingHapticNotch(targets, type)
  }

  internal fun build(): List<BearingHapticNotch> = notches.toList()
}

internal data class BearingHapticNotch(val targets: BearingTargets, val type: HapticFeedbackType)
