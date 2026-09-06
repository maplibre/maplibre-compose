package org.maplibre.compose.map

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal data class PanMomentum(
  val enabled: Boolean = true,
  val minimumSpeed: Double = 1000.0,
  val baseTime: Duration = 150.milliseconds,
  val durationScale: Double = 1.0,
)

internal data class PanMomentumOverride(
  val enabled: Boolean? = null,
  val minimumSpeed: Double? = null,
  val baseTime: Duration? = null,
  val durationScale: Double? = null,
) {
  fun resolve(base: PanMomentum): PanMomentum =
    PanMomentum(
      enabled = enabled ?: base.enabled,
      minimumSpeed = minimumSpeed ?: base.minimumSpeed,
      baseTime = baseTime ?: base.baseTime,
      durationScale = durationScale ?: base.durationScale,
    )
}

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

internal data class VelocityMomentum(
  val enabled: Boolean = true,
  val durationScale: Double = 1.0,
  val maximumDuration: Duration = 300.milliseconds,
)

internal data class VelocityMomentumOverride(
  val enabled: Boolean? = null,
  val durationScale: Double? = null,
  val maximumDuration: Duration? = null,
) {
  fun resolve(base: VelocityMomentum): VelocityMomentum =
    VelocityMomentum(
      enabled = enabled ?: base.enabled,
      durationScale = durationScale ?: base.durationScale,
      maximumDuration = maximumDuration ?: base.maximumDuration,
    )
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

internal data class TiltMomentum(
  val enabled: Boolean = true,
  val minimumSpeed: Double = 5.0,
  val duration: Duration = 150.milliseconds,
)

internal data class TiltMomentumOverride(
  val enabled: Boolean? = null,
  val minimumSpeed: Double? = null,
  val duration: Duration? = null,
) {
  fun resolve(base: TiltMomentum): TiltMomentum =
    TiltMomentum(
      enabled = enabled ?: base.enabled,
      minimumSpeed = minimumSpeed ?: base.minimumSpeed,
      duration = duration ?: base.duration,
    )
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
