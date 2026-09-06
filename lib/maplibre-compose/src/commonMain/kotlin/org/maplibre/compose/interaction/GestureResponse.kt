package org.maplibre.compose.interaction

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
