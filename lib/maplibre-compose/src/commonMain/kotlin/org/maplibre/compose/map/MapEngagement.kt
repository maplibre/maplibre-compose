package org.maplibre.compose.map

/** What made a focused map consume the keys that pan, zoom, rotate, and tilt. */
public enum class MapEngagement {
  /** The map passes those keys to focus traversal. */
  None,

  /** Enter, numpad Enter, or D-pad center engaged the map. Escape or Back disengages it. */
  Keyboard,

  /** A pointer press engaged the map. Escape disengages it. */
  Pointer,
}
