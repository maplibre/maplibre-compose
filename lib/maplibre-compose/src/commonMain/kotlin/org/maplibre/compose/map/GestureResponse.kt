package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Input anchors the point under the pointer; CameraCenter preserves the padded camera target. */
public enum class GestureAnchor {
  Input,
  CameraCenter,
}

/** The sign of vertical displacement used by quick zoom. */
public enum class QuickZoomDirection {
  DownZoomsIn,
  UpZoomsIn,
}

/** Response to an unconsumed tap-family event. */
public enum class TapCameraAction {
  ZoomIn,
  ZoomOut,
}

/**
 * The camera response of a custom binding. [Custom] leaves the response to its onEvent callback.
 */
public enum class DragAction {
  Pan,
  RotateTilt,
  Zoom,
  BoxZoom,
  Custom,
}

/** Screen-space pan continuation. Speeds are dp/second and [baseTime] contributes to duration. */
@Immutable
public data class Fling(
  val minimumSpeed: Double = 1000.0,
  val baseTime: Duration = 150.milliseconds,
  val durationScale: Double = 1.0,
) {
  init {
    requireNonnegativeFinite(minimumSpeed, "minimumSpeed")
    requireNonnegativeFinite(baseTime, "baseTime")
    requireNonnegativeFinite(durationScale, "durationScale")
  }
}

/** Retained touch zoom or rotation momentum, with a bounded duration. */
@Immutable
public data class GestureVelocityContinuation(
  val durationScale: Double = 1.0,
  val maximumDuration: Duration = 300.milliseconds,
) {
  init {
    requireNonnegativeFinite(durationScale, "durationScale")
    requireNonnegativeFinite(maximumDuration, "maximumDuration")
  }
}

/** Pitch continuation with linear velocity decay. [minimumSpeed] is in degrees/second. */
@Immutable
public data class TiltContinuation(
  val minimumSpeed: Double = 5.0,
  val duration: Duration = 150.milliseconds,
) {
  init {
    requireNonnegativeFinite(minimumSpeed, "minimumSpeed")
    requireNonnegativeFinite(duration, "duration")
  }
}

internal fun requireNonnegativeFinite(value: Double, name: String) {
  require(value.isFinite() && value >= 0.0) { "$name must be finite and nonnegative" }
}

internal fun requireNonnegativeFinite(value: Duration, name: String) {
  require(value.isFinite() && !value.isNegative()) { "$name must be finite and nonnegative" }
}
