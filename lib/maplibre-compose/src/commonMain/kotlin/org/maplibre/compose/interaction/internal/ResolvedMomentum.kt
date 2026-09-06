package org.maplibre.compose.interaction.internal

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

internal data class VelocityMomentum(
  val enabled: Boolean = true,
  val durationScale: Double = 1.0,
  val maximumDuration: Duration = 600.milliseconds,
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

internal data class TiltMomentum(
  val enabled: Boolean = true,
  val minimumSpeed: Double = 5.0,
  val duration: Duration = 300.milliseconds,
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
