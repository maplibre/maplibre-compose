package org.maplibre.compose.interaction.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal data class PanMomentum(
  val enabled: Boolean = true,
  val minimumSpeed: Double = 250.0,
  val baseTime: Duration = 150.milliseconds,
  val durationScale: Double = 1.0,
)

internal data class VelocityMomentum(
  val enabled: Boolean = true,
  val durationScale: Double = 1.0,
  val maximumDuration: Duration = 600.milliseconds,
)

internal data class TiltMomentum(
  val enabled: Boolean = true,
  val minimumSpeed: Double = 5.0,
  val duration: Duration = 300.milliseconds,
)
