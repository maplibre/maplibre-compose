package org.maplibre.compose.map

import kotlin.time.Duration

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

internal fun requireNonnegativeFinite(value: Double, name: String) {
  require(value.isFinite() && value >= 0.0) { "$name must be finite and nonnegative" }
}

internal fun requireNonnegativeFinite(value: Duration, name: String) {
  require(value.isFinite() && !value.isNegative()) { "$name must be finite and nonnegative" }
}
