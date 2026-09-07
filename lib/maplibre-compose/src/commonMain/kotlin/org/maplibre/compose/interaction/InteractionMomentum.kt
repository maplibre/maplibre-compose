package org.maplibre.compose.interaction

import kotlin.time.Duration
import org.maplibre.compose.interaction.internal.PanMomentum
import org.maplibre.compose.interaction.internal.TiltMomentum
import org.maplibre.compose.interaction.internal.VelocityMomentum
import org.maplibre.compose.interaction.internal.requireNonnegativeFinite

/** Pan momentum after normal release. Speeds are dp/second; cancellation starts no momentum. */
@MapInteractionDsl
public class PanMomentumBuilder internal constructor(from: PanMomentum) {
  public var enabled: Boolean = from.enabled

  public var minimumSpeed: Double = from.minimumSpeed

  public var baseTime: Duration = from.baseTime

  public var durationScale: Double = from.durationScale

  internal fun build(): PanMomentum =
    PanMomentum(enabled, minimumSpeed, baseTime, durationScale).also { value ->
      requireNonnegativeFinite(value.minimumSpeed, "minimumSpeed")
      requireNonnegativeFinite(value.baseTime, "baseTime")
      requireNonnegativeFinite(value.durationScale, "durationScale")
    }
}

/** Zoom or rotation momentum after normal release, with a bounded duration. */
@MapInteractionDsl
public class VelocityMomentumBuilder internal constructor(from: VelocityMomentum) {
  public var enabled: Boolean = from.enabled

  public var durationScale: Double = from.durationScale

  public var maximumDuration: Duration = from.maximumDuration

  internal fun build(): VelocityMomentum =
    VelocityMomentum(enabled, durationScale, maximumDuration).also { value ->
      requireNonnegativeFinite(value.durationScale, "durationScale")
      requireNonnegativeFinite(value.maximumDuration, "maximumDuration")
    }
}

/** Pitch momentum after normal release. Speeds are degrees/second. */
@MapInteractionDsl
public class TiltMomentumBuilder internal constructor(from: TiltMomentum) {
  public var enabled: Boolean = from.enabled

  public var minimumSpeed: Double = from.minimumSpeed

  public var duration: Duration = from.duration

  internal fun build(): TiltMomentum =
    TiltMomentum(enabled, minimumSpeed, duration).also { value ->
      requireNonnegativeFinite(value.minimumSpeed, "minimumSpeed")
      requireNonnegativeFinite(value.duration, "duration")
    }
}
