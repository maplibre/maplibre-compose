package org.maplibre.compose.interaction

import kotlin.time.Duration
import org.maplibre.compose.interaction.internal.Snapping
import org.maplibre.compose.interaction.internal.requireNonnegativeFinite

/**
 * Where the zoom, the rotation, or the pitch settles after input that changed it.
 *
 * The targets lie every [spacing] from [offset], in the part's unit: zoom levels for zoom, and
 * degrees for rotation and pitch. Rotation targets wrap around the circle, and the rotation takes
 * the shorter way to the nearest one. Once the part's momentum ends, or right away when the release
 * starts none, the part eases to the nearest target over [duration] if it is within [tolerance] of
 * it, and otherwise stays where it is.
 *
 * New input or a camera command stops the settling. Entering a snapping block turns snapping on
 * unless the block sets [enabled] to false. Building the options throws [IllegalArgumentException]
 * unless [spacing] is positive and finite, [offset] is finite, and [tolerance] and [duration] are
 * finite and not negative.
 */
@MapInteractionDsl
public class SnappingBuilder internal constructor(from: Snapping) {
  /** Whether to settle. */
  public var enabled: Boolean = true

  /** The distance between neighboring targets. */
  public var spacing: Double = from.spacing

  /** One target; the others lie every [spacing] from it. */
  public var offset: Double = from.offset

  /** The farthest from a target, inclusive, that the part settles on it. */
  public var tolerance: Double = from.tolerance

  /** How long the settling takes. Zero settles at once. */
  public var duration: Duration = from.duration

  internal fun build(): Snapping {
    require(spacing.isFinite() && spacing > 0.0) {
      "spacing must be positive and finite, was $spacing"
    }
    require(offset.isFinite()) { "offset must be finite, was $offset" }
    requireNonnegativeFinite(tolerance, "tolerance")
    requireNonnegativeFinite(duration, "duration")
    return Snapping(enabled, spacing, offset, tolerance, duration)
  }
}
