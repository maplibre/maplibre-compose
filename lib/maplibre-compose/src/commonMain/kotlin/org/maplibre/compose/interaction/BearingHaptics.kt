package org.maplibre.compose.interaction

/**
 * Relative emphasis. Platforms may use the same feedback for several levels, or remain silent.
 * macOS uses the trackpad alignment pattern for every level.
 */
public enum class HapticEmphasis {
  Subtle,
  Standard,
  Emphasized,
}

/** Haptic notches during pointer rotation, independent of bearing snapping. */
@MapInteractionDsl
public class BearingHapticsBuilder internal constructor() {
  private val notches = mutableListOf<BearingHapticNotch>()

  /**
   * Adds feedback near or across [targets]. Overlapping notches produce one event with the greatest
   * emphasis. Feedback requires moving away before re-entry and is rate limited during fast
   * rotation. Starting at a notch is silent; crossings between input samples still produce
   * feedback.
   */
  public fun notch(targets: BearingTargets, emphasis: HapticEmphasis = HapticEmphasis.Standard) {
    notches += BearingHapticNotch(targets, emphasis)
  }

  internal fun build(): List<BearingHapticNotch> = notches.toList()
}

internal data class BearingHapticNotch(val targets: BearingTargets, val emphasis: HapticEmphasis)
