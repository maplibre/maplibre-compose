package org.maplibre.compose.interaction

import kotlin.time.Duration
import org.maplibre.compose.interaction.internal.PanMomentum
import org.maplibre.compose.interaction.internal.PitchMomentum
import org.maplibre.compose.interaction.internal.VelocityMomentum
import org.maplibre.compose.interaction.internal.requireNonnegativeFinite

/**
 * Pan momentum after normal release; cancellation starts no momentum.
 *
 * The momentum lasts [baseTime] plus 1 ms for each 10.5 dp/second of release speed, multiplied by
 * [durationScale]. Every value must be finite and not negative. Building the options throws
 * [IllegalArgumentException] otherwise.
 */
@MapInteractionDsl
public class PanMomentumBuilder internal constructor(from: PanMomentum) {
  /** Whether a release continues the pan. */
  public var enabled: Boolean = from.enabled

  /** Slowest release speed, in dp per second, that starts momentum. */
  public var minimumSpeedDpPerSecond: Double = from.minimumSpeedDpPerSecond

  /** Part of the momentum duration that does not depend on release speed. */
  public var baseTime: Duration = from.baseTime

  /** Multiplier for the momentum duration. Zero starts no momentum. */
  public var durationScale: Double = from.durationScale

  internal fun build(): PanMomentum =
    PanMomentum(enabled, minimumSpeedDpPerSecond, baseTime, durationScale).also { value ->
      requireNonnegativeFinite(value.minimumSpeedDpPerSecond, "minimumSpeedDpPerSecond")
      requireNonnegativeFinite(value.baseTime, "baseTime")
      requireNonnegativeFinite(value.durationScale, "durationScale")
    }
}

/**
 * Zoom or rotation momentum after normal release, with a bounded duration.
 *
 * Every value must be finite and not negative. Building the options throws
 * [IllegalArgumentException] otherwise.
 */
@MapInteractionDsl
public class VelocityMomentumBuilder internal constructor(from: VelocityMomentum) {
  /** Whether a release continues the zoom or rotation. */
  public var enabled: Boolean = from.enabled

  /** Multiplier for the standard momentum duration of 600 ms. Zero starts no momentum. */
  public var durationScale: Double = from.durationScale

  /** Longest momentum duration after [durationScale] applies. Zero starts no momentum. */
  public var maximumDuration: Duration = from.maximumDuration

  internal fun build(): VelocityMomentum =
    VelocityMomentum(enabled, durationScale, maximumDuration).also { value ->
      requireNonnegativeFinite(value.durationScale, "durationScale")
      requireNonnegativeFinite(value.maximumDuration, "maximumDuration")
    }
}

/**
 * Pitch momentum after normal release.
 *
 * Every value must be finite and not negative. Building the options throws
 * [IllegalArgumentException] otherwise.
 */
@MapInteractionDsl
public class PitchMomentumBuilder internal constructor(from: PitchMomentum) {
  /** Whether a release continues the pitch change. */
  public var enabled: Boolean = from.enabled

  /** Slowest release speed, in degrees per second, that starts momentum. */
  public var minimumSpeedDegreesPerSecond: Double = from.minimumSpeedDegreesPerSecond

  /** Time over which the pitch speed slows to zero. Zero starts no momentum. */
  public var duration: Duration = from.duration

  internal fun build(): PitchMomentum =
    PitchMomentum(enabled, minimumSpeedDegreesPerSecond, duration).also { value ->
      requireNonnegativeFinite(value.minimumSpeedDegreesPerSecond, "minimumSpeedDegreesPerSecond")
      requireNonnegativeFinite(value.duration, "duration")
    }
}
