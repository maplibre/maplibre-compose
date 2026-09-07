package org.maplibre.compose.interaction

/** Camera response to a drag. [None] leaves matching input unclaimed. */
public enum class DragResponse {
  Pan,
  RotateTilt,
  FitBounds,
  None,
}

/** Camera response to scrolling. [None] leaves matching input unclaimed. */
public enum class ScrollResponse {
  Pan,
  /** Zooms from vertical scrolling. Horizontal-only events remain unclaimed. */
  Zoom,
  None,
}

/** Camera response after an unhandled tap. */
public enum class TapResponse {
  ZoomIn,
  ZoomOut,
  None,
}

/** Camera or focus response to a key. */
public enum class KeyResponse {
  PanLeft,
  PanRight,
  PanUp,
  PanDown,
  ZoomIn,
  ZoomOut,
  RotateLeft,
  RotateRight,
  TiltUp,
  TiltDown,
  Engage,
  Disengage,
  Back,
  None;

  internal val isCamera: Boolean
    get() = this !in setOf(Engage, Disengage, Back, None)
}
