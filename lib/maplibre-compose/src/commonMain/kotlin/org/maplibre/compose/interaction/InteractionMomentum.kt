package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CubicBezier
import org.maplibre.compose.interaction.internal.Momentum
import org.maplibre.compose.interaction.internal.MomentumDecay
import org.maplibre.compose.interaction.internal.requireNonnegativeFinite

/**
 * How one part of the camera keeps moving after the user releases a gesture: the pan, the zoom, the
 * rotation, or the pitch. The part starts at the speed of the release and slows down by its decay,
 * which is either [friction] or [ease].
 *
 * Speeds and distances use the part's unit: dp for pan, zoom levels for zoom, and degrees for
 * rotation and pitch. Speeds are per second.
 */
@MapInteractionDsl
public class MomentumBuilder
internal constructor(from: Momentum, private val defaultFriction: MomentumDecay.Friction) {
  /** Whether a release keeps this part moving. */
  public var enabled: Boolean = from.enabled

  private var decay: MomentumDecay = from.decay

  /**
   * Slows the part by friction: its speed halves every [FrictionMomentumBuilder.halfLife] until it
   * drops to [FrictionMomentumBuilder.stopSpeed]. The block starts from this part's friction
   * settings, or from its defaults when the part eases.
   */
  public fun friction(block: FrictionMomentumBuilder.() -> Unit = {}) {
    decay =
      FrictionMomentumBuilder(decay as? MomentumDecay.Friction ?: defaultFriction)
        .apply(block)
        .build()
  }

  /**
   * Moves the part along a glide that [planner] plans from the speed of each release, the way a
   * platform's own fling does. The block starts from this part's ease settings, or from the minimum
   * speed and maximum distance of its friction.
   */
  public fun ease(planner: MomentumPlanner, block: EaseMomentumBuilder.() -> Unit = {}) {
    val current = decay
    val from =
      current as? MomentumDecay.Ease
        ?: MomentumDecay.Ease(planner, current.minSpeed, current.maxDistance)
    decay = EaseMomentumBuilder(from.copy(planner = planner)).apply(block).build()
  }

  internal fun build(): Momentum = Momentum(enabled, decay)
}

/**
 * Friction for one part's momentum. Speeds and distances use the part's unit; see
 * [MomentumBuilder].
 *
 * The speed halves every [halfLife]. A release faster than [maxSpeed] starts at [maxSpeed], and a
 * release that would move farther than [maxDistance] starts slower, so it ends sooner. Building the
 * options throws [IllegalArgumentException] unless [halfLife], [stopSpeed], [maxSpeed], and
 * [maxDistance] are positive and [minSpeed] is finite and not negative. [maxSpeed] and
 * [maxDistance] can be [Double.POSITIVE_INFINITY].
 */
@MapInteractionDsl
public class FrictionMomentumBuilder internal constructor(from: MomentumDecay.Friction) {
  /** How long the speed takes to halve. */
  public var halfLife: Duration = from.halfLife

  /** The speed at which the part stops. */
  public var stopSpeed: Double = from.stopSpeed

  /** The slowest release that starts momentum. */
  public var minSpeed: Double = from.minSpeed

  /** The fastest speed the part starts at. */
  public var maxSpeed: Double = from.maxSpeed

  /** The farthest the part moves. */
  public var maxDistance: Double = from.maxDistance

  internal fun build(): MomentumDecay.Friction {
    require(halfLife.isPositive() && halfLife.isFinite()) {
      "halfLife must be positive and finite, was $halfLife"
    }
    require(stopSpeed.isFinite() && stopSpeed > 0.0) {
      "stopSpeed must be positive and finite, was $stopSpeed"
    }
    requireNonnegativeFinite(minSpeed, "minSpeed")
    require(maxSpeed > 0.0) { "maxSpeed must be positive, was $maxSpeed" }
    require(maxDistance > 0.0) { "maxDistance must be positive, was $maxDistance" }
    return MomentumDecay.Friction(halfLife, stopSpeed, minSpeed, maxSpeed, maxDistance)
  }
}

/**
 * An eased glide for one part's momentum. Speeds and distances use the part's unit; see
 * [MomentumBuilder].
 *
 * The planner chooses the distance, the duration, and the curve of each glide. A glide moves at
 * most [maxDistance]. Building the options throws [IllegalArgumentException] unless [minSpeed] is
 * finite and not negative and [maxDistance] is positive. [maxDistance] can be
 * [Double.POSITIVE_INFINITY].
 */
@MapInteractionDsl
public class EaseMomentumBuilder internal constructor(private val from: MomentumDecay.Ease) {
  /** The slowest release that starts momentum. */
  public var minSpeed: Double = from.minSpeed

  /** The farthest the part moves. */
  public var maxDistance: Double = from.maxDistance

  internal fun build(): MomentumDecay.Ease {
    requireNonnegativeFinite(minSpeed, "minSpeed")
    require(maxDistance > 0.0) { "maxDistance must be positive, was $maxDistance" }
    return from.copy(minSpeed = minSpeed, maxDistance = maxDistance)
  }
}

/**
 * Plans the glide of an eased momentum from the speed of a release.
 *
 * The map compares interaction settings to decide whether they changed, and a change cancels input
 * in progress. Give a planner that equals itself across compositions, such as an `object` or a data
 * class, rather than a new lambda on each composition.
 */
public fun interface MomentumPlanner {
  /**
   * Returns the glide for a release at [speed], in the part's unit per second, or null when the
   * release should not move. [speed] is positive.
   */
  public fun plan(speed: Double): MomentumGlide?

  public companion object {
    /**
     * Android's fling: the distance and duration that Android's `OverScroller` gives a fling at the
     * same speed, along the curve its fling follows. Plans pans in dp.
     */
    public val AndroidFling: MomentumPlanner = AndroidFlingPlanner
  }
}

/**
 * One planned glide: the part moves [distance], in the part's unit, over [duration], and [easing]
 * gives the share of the distance covered over the share of the duration.
 *
 * [distance] and [duration] must be positive and finite, and every control point of [easing] must
 * lie in `[0, 1]`, so that the glide never passes its distance. The constructor throws
 * [IllegalArgumentException] otherwise.
 */
@Immutable
public data class MomentumGlide(
  val distance: Double,
  val duration: Duration,
  val easing: CubicBezier,
) {
  init {
    require(distance.isFinite() && distance > 0.0) {
      "distance must be positive and finite, was $distance"
    }
    require(duration.isPositive() && duration.isFinite()) {
      "duration must be positive and finite, was $duration"
    }
    require(easing.y1 in 0.0..1.0 && easing.y2 in 0.0..1.0) {
      "easing control point y values must lie in [0, 1]: ${easing.y1}, ${easing.y2}"
    }
  }
}

/**
 * The spline of Android's `OverScroller`, which `Scroller`, `ScrollView`, and `RecyclerView` fling
 * along, at 160 dpi so that its pixels are dp.
 */
private data object AndroidFlingPlanner : MomentumPlanner {
  private const val Inflexion = 0.35
  /** `ViewConfiguration.getScrollFriction()`. */
  private const val ScrollFriction = 0.015
  /** Earth's gravity, in inches per second squared at 160 dpi, times Android's 0.84. */
  private const val PhysicalCoefficient = 9.80665 * 39.37 * 160.0 * 0.84
  private val DecelerationRate = ln(0.78) / ln(0.9)

  /** Android's fling curve, which `mln_momentum_decay` documents. */
  private val easing = CubicBezier(0.175, 0.5, 0.35, 1.0)

  override fun plan(speed: Double): MomentumGlide? {
    if (!(speed > 0.0) || !speed.isFinite()) return null
    val deceleration = ln(Inflexion * speed / (ScrollFriction * PhysicalCoefficient))
    val distance =
      ScrollFriction *
        PhysicalCoefficient *
        exp(DecelerationRate / (DecelerationRate - 1.0) * deceleration)
    val duration = (1000.0 * exp(deceleration / (DecelerationRate - 1.0))).milliseconds
    if (!(distance > 0.0) || !distance.isFinite() || !duration.isPositive()) return null
    return MomentumGlide(distance, duration, easing)
  }

  override fun toString(): String = "MomentumPlanner.AndroidFling"
}
