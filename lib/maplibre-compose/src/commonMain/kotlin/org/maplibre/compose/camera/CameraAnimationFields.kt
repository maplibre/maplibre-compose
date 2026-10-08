package org.maplibre.compose.camera

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.util.formatToString

internal data class EaseFields(val duration: Duration, val easing: CubicBezier) {
  class Builder(from: EaseFields?) {
    var duration: Duration = from?.duration ?: 300.milliseconds
    var easing: CubicBezier = from?.easing ?: CubicBezier.Default

    fun build() = EaseFields(duration, easing)
  }

  override fun toString(): String =
    formatToString("Ease", "duration" to duration, "easing" to easing)
}

internal data class FlyFields(
  val duration: Duration?,
  val speed: Double,
  val easing: CubicBezier,
) {
  init {
    require(speed > 0.0) { "Flight speed must be positive: $speed" }
  }

  class Builder(from: FlyFields?) {
    var duration: Duration? = from?.duration
    var speed: Double = from?.speed ?: DefaultSpeed
    var easing: CubicBezier = from?.easing ?: CubicBezier.Default

    fun build() = FlyFields(duration, speed, easing)
  }

  override fun toString(): String =
    formatToString("Fly", "duration" to duration, "speed" to speed, "easing" to easing)

  companion object {
    const val DefaultSpeed: Double = 1.2 * 1.42
  }
}
