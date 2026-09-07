package org.maplibre.compose.interaction

import kotlin.math.abs

/**
 * Settles near a target after rotation input and its momentum finish normally.
 *
 * Snapping is disabled until configured. The initial targets and tolerance snap to north within 7°.
 */
@MapInteractionDsl
public class BearingSnappingBuilder internal constructor(from: BearingSnapping) {
  /** Whether to settle. Entering a snapping block enables it unless explicitly disabled. */
  public var enabled: Boolean = true

  public var targets: BearingTargets = from.targets

  /**
   * Maximum circular distance in degrees, inclusive, from the nearest target. Must be in [0, 180].
   * Equidistant targets choose the lowest normalized bearing. Bearings outside this range stay
   * free.
   */
  public var tolerance: Double = from.tolerance

  internal fun build(): BearingSnapping {
    require(tolerance.isFinite() && tolerance in 0.0..180.0) { "tolerance must be in [0, 180]" }
    return BearingSnapping(enabled, targets, tolerance)
  }
}

internal data class BearingSnapping(
  val enabled: Boolean = false,
  val targets: BearingTargets = BearingTargets.at(0.0),
  val tolerance: Double = 7.0,
) {
  fun delta(bearing: Double): Double? =
    if (!enabled || !bearing.isFinite()) null
    else targets.nearestDelta(bearing).takeIf { it != 0.0 && abs(it) <= tolerance }
}
