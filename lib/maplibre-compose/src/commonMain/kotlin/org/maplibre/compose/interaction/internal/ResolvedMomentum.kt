package org.maplibre.compose.interaction.internal

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.interaction.MomentumPlanner

/** One part's release momentum. */
internal data class Momentum(val enabled: Boolean, val decay: MomentumDecay)

/**
 * How one part's momentum slows, in the part's unit, as `mln_momentum_decay` describes it. Infinite
 * limits mean none.
 */
internal sealed interface MomentumDecay {
  val minSpeed: Double
  val maxDistance: Double

  data class Friction(
    val halfLife: Duration,
    val stopSpeed: Double,
    override val minSpeed: Double,
    val maxSpeed: Double,
    override val maxDistance: Double,
  ) : MomentumDecay

  data class Ease(
    val planner: MomentumPlanner,
    override val minSpeed: Double,
    override val maxDistance: Double,
  ) : MomentumDecay
}

/**
 * The defaults that `mln_gesture_momentum` documents, so both engines glide the same. Android pans
 * ease along Android's own fling instead; see [PlatformPanDecay].
 */
internal object MomentumDefaults {
  private val HalfLife = 140.milliseconds

  val PanFriction =
    MomentumDecay.Friction(HalfLife, 10.0, 200.0, 10_000.0, Double.POSITIVE_INFINITY)
  val ZoomFriction = MomentumDecay.Friction(HalfLife, 0.01, 0.0, Double.POSITIVE_INFINITY, 2.5)
  val BearingFriction =
    MomentumDecay.Friction(HalfLife, 1.0, 60.0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
  val PitchFriction =
    MomentumDecay.Friction(HalfLife, 1.0, 5.0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)

  private val SnapDuration = 300.milliseconds
  val ZoomSnapping = Snapping(false, 1.0, 0.0, 0.5, SnapDuration)
  val BearingSnapping = Snapping(true, 360.0, 0.0, 7.0, SnapDuration)
  val PitchSnapping = Snapping(false, 90.0, 0.0, 7.0, SnapDuration)
}

/** How pans slow on this platform by default. */
internal expect val PlatformPanDecay: MomentumDecay

/**
 * Where a part settles after input that changed it, as `mln_momentum_snap` describes it: on the
 * nearest of the targets every [spacing] from [offset], when it is within [tolerance].
 */
internal data class Snapping(
  val enabled: Boolean,
  val spacing: Double,
  val offset: Double,
  val tolerance: Double,
  val duration: Duration,
) {
  /**
   * The change that settles [value], or zero when it stays. A [circular] value, the bearing, has
   * its targets within one turn from [offset] and takes the shorter way around to the nearest.
   * Matches MapLibre Native's momentum snap.
   */
  fun delta(value: Double, circular: Boolean): Double {
    if (!(spacing > 0.0) || !value.isFinite()) return 0.0
    val delta =
      if (circular) {
        val turn = value - offset - 360.0 * floor((value - offset) / 360.0)
        val below = floor(turn / spacing) * spacing
        val above = min(below + spacing, 360.0)
        if (turn - below <= above - turn) below - turn else above - turn
      } else {
        offset + roundHalfAwayFromZero((value - offset) / spacing) * spacing - value
      }
    return if (abs(delta) <= tolerance) delta else 0.0
  }
}

/** C++'s `std::round`, which Native's snap uses: halfway values round away from zero. */
private fun roundHalfAwayFromZero(value: Double): Double =
  if (value < 0.0) -floor(-value + 0.5) else floor(value + 0.5)
