package org.maplibre.compose.interaction

/** Input anchors the point under the pointer; CameraCenter preserves the padded camera target. */
public enum class GestureAnchor {
  Input,
  CameraCenter,
}

/**
 * Which vertical drag direction zooms in during quick zoom: tap, then press again and drag up or
 * down.
 */
public enum class QuickZoomDirection {
  /** Dragging down zooms in and dragging up zooms out, as in Google Maps. */
  DownZoomsIn,

  /** Dragging up zooms in and dragging down zooms out, as in Apple Maps. */
  UpZoomsIn,
}
