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

  fun merge(other: PanMomentumOverride): PanMomentumOverride =
    PanMomentumOverride(
      enabled = other.enabled ?: enabled,
      minimumSpeed = other.minimumSpeed ?: minimumSpeed,
      baseTime = other.baseTime ?: baseTime,
      durationScale = other.durationScale ?: durationScale,
    )
}

/** Pan momentum after normal release. Speeds are dp/second; cancellation starts no momentum. */
@MapInteractionDsl
public class PanMomentumBuilder internal constructor(private val from: PanMomentum) {
  private var changes = PanMomentumOverride()
  public var enabled: Boolean
    get() = changes.enabled ?: from.enabled
    set(value) {
      changes = changes.copy(enabled = value)
    }

  public var minimumSpeed: Double
    get() = changes.minimumSpeed ?: from.minimumSpeed
    set(value) {
      changes = changes.copy(minimumSpeed = value)
    }

  public var baseTime: Duration
    get() = changes.baseTime ?: from.baseTime
    set(value) {
      changes = changes.copy(baseTime = value)
    }

  public var durationScale: Double
    get() = changes.durationScale ?: from.durationScale
    set(value) {
      changes = changes.copy(durationScale = value)
    }

  internal fun build(): PanMomentum =
    changes.resolve(from).also { value ->
      requireNonnegativeFinite(value.minimumSpeed, "minimumSpeed")
      requireNonnegativeFinite(value.baseTime, "baseTime")
      requireNonnegativeFinite(value.durationScale, "durationScale")
    }

  internal fun override(): PanMomentumOverride {
    build()
    return changes
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

  fun merge(other: VelocityMomentumOverride): VelocityMomentumOverride =
    VelocityMomentumOverride(
      enabled = other.enabled ?: enabled,
      durationScale = other.durationScale ?: durationScale,
      maximumDuration = other.maximumDuration ?: maximumDuration,
    )
}

/** Zoom or rotation momentum after normal release, with a bounded duration. */
@MapInteractionDsl
public class VelocityMomentumBuilder internal constructor(private val from: VelocityMomentum) {
  private var changes = VelocityMomentumOverride()
  public var enabled: Boolean
    get() = changes.enabled ?: from.enabled
    set(value) {
      changes = changes.copy(enabled = value)
    }

  public var durationScale: Double
    get() = changes.durationScale ?: from.durationScale
    set(value) {
      changes = changes.copy(durationScale = value)
    }

  public var maximumDuration: Duration
    get() = changes.maximumDuration ?: from.maximumDuration
    set(value) {
      changes = changes.copy(maximumDuration = value)
    }

  internal fun build(): VelocityMomentum =
    changes.resolve(from).also { value ->
      requireNonnegativeFinite(value.durationScale, "durationScale")
      requireNonnegativeFinite(value.maximumDuration, "maximumDuration")
    }

  internal fun override(): VelocityMomentumOverride {
    build()
    return changes
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

  fun merge(other: TiltMomentumOverride): TiltMomentumOverride =
    TiltMomentumOverride(
      enabled = other.enabled ?: enabled,
      minimumSpeed = other.minimumSpeed ?: minimumSpeed,
      duration = other.duration ?: duration,
    )
}

/** Pitch momentum after normal release. Speeds are degrees/second. */
@MapInteractionDsl
public class TiltMomentumBuilder internal constructor(private val from: TiltMomentum) {
  private var changes = TiltMomentumOverride()
  public var enabled: Boolean
    get() = changes.enabled ?: from.enabled
    set(value) {
      changes = changes.copy(enabled = value)
    }

  public var minimumSpeed: Double
    get() = changes.minimumSpeed ?: from.minimumSpeed
    set(value) {
      changes = changes.copy(minimumSpeed = value)
    }

  public var duration: Duration
    get() = changes.duration ?: from.duration
    set(value) {
      changes = changes.copy(duration = value)
    }

  internal fun build(): TiltMomentum =
    changes.resolve(from).also { value ->
      requireNonnegativeFinite(value.minimumSpeed, "minimumSpeed")
      requireNonnegativeFinite(value.duration, "duration")
    }

  internal fun override(): TiltMomentumOverride {
    build()
    return changes
  }
}
