package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable

@Immutable
public enum class CameraMoveReason {
  /** The camera hasn't moved yet. */
  NONE,

  /** The camera moved for a reason we don't understand. File a bug report! */
  UNKNOWN,

  /**
   * Camera movement was initiated by the user manipulating the map by panning, zooming, rotating,
   * or tilting.
   */
  GESTURE,

  /** Camera movement was initiated by a call to the public API, or by the compass ornament. */
  PROGRAMMATIC,
}
