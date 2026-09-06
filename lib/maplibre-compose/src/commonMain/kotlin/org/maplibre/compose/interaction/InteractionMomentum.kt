package org.maplibre.compose.interaction

import kotlin.time.Duration
import org.maplibre.compose.interaction.internal.PanMomentum
import org.maplibre.compose.interaction.internal.PanMomentumOverride
import org.maplibre.compose.interaction.internal.TiltMomentum
import org.maplibre.compose.interaction.internal.TiltMomentumOverride
import org.maplibre.compose.interaction.internal.VelocityMomentum
import org.maplibre.compose.interaction.internal.VelocityMomentumOverride
import org.maplibre.compose.interaction.internal.requireNonnegativeFinite

/** Pan momentum after normal release. Speeds are dp/second; cancellation starts no momentum. */
@MapInteractionDsl
public class PanMomentumBuilder
internal constructor(
  private val from: PanMomentum,
  initial: PanMomentumOverride = PanMomentumOverride(),
) {
  internal var overrides: PanMomentumOverride = initial
    private set

  public var enabled: Boolean
    get() = overrides.enabled ?: from.enabled
    set(value) {
      overrides = overrides.copy(enabled = value)
    }

  public var minimumSpeed: Double
    get() = overrides.minimumSpeed ?: from.minimumSpeed
    set(value) {
      overrides = overrides.copy(minimumSpeed = value)
    }

  public var baseTime: Duration
    get() = overrides.baseTime ?: from.baseTime
    set(value) {
      overrides = overrides.copy(baseTime = value)
    }

  public var durationScale: Double
    get() = overrides.durationScale ?: from.durationScale
    set(value) {
      overrides = overrides.copy(durationScale = value)
    }

  internal fun build(base: PanMomentum = from): PanMomentum =
    overrides.resolve(base).also { value ->
      requireNonnegativeFinite(value.minimumSpeed, "minimumSpeed")
      requireNonnegativeFinite(value.baseTime, "baseTime")
      requireNonnegativeFinite(value.durationScale, "durationScale")
    }
}

/** Zoom or rotation momentum after normal release, with a bounded duration. */
@MapInteractionDsl
public class VelocityMomentumBuilder
internal constructor(
  private val from: VelocityMomentum,
  initial: VelocityMomentumOverride = VelocityMomentumOverride(),
) {
  internal var overrides: VelocityMomentumOverride = initial
    private set

  public var enabled: Boolean
    get() = overrides.enabled ?: from.enabled
    set(value) {
      overrides = overrides.copy(enabled = value)
    }

  public var durationScale: Double
    get() = overrides.durationScale ?: from.durationScale
    set(value) {
      overrides = overrides.copy(durationScale = value)
    }

  public var maximumDuration: Duration
    get() = overrides.maximumDuration ?: from.maximumDuration
    set(value) {
      overrides = overrides.copy(maximumDuration = value)
    }

  internal fun build(base: VelocityMomentum = from): VelocityMomentum =
    overrides.resolve(base).also { value ->
      requireNonnegativeFinite(value.durationScale, "durationScale")
      requireNonnegativeFinite(value.maximumDuration, "maximumDuration")
    }
}

/** Pitch momentum after normal release. Speeds are degrees/second. */
@MapInteractionDsl
public class TiltMomentumBuilder
internal constructor(
  private val from: TiltMomentum,
  initial: TiltMomentumOverride = TiltMomentumOverride(),
) {
  internal var overrides: TiltMomentumOverride = initial
    private set

  public var enabled: Boolean
    get() = overrides.enabled ?: from.enabled
    set(value) {
      overrides = overrides.copy(enabled = value)
    }

  public var minimumSpeed: Double
    get() = overrides.minimumSpeed ?: from.minimumSpeed
    set(value) {
      overrides = overrides.copy(minimumSpeed = value)
    }

  public var duration: Duration
    get() = overrides.duration ?: from.duration
    set(value) {
      overrides = overrides.copy(duration = value)
    }

  internal fun build(base: TiltMomentum = from): TiltMomentum =
    overrides.resolve(base).also { value ->
      requireNonnegativeFinite(value.minimumSpeed, "minimumSpeed")
      requireNonnegativeFinite(value.duration, "duration")
    }
}
