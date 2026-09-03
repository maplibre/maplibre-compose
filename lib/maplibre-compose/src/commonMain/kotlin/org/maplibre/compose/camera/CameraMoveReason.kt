package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable

/** What started the most recent camera movement. */
@Immutable
public enum class CameraMoveReason {
  /** The camera has not moved yet. */
  NONE,

  /**
   * The movement has no attributed cause. The library reports every movement as [GESTURE] or
   * [PROGRAMMATIC], so it never reports this value.
   */
  UNKNOWN,

  /** A gesture on the map moved the camera: a pan, a zoom, a rotation, or a tilt. */
  GESTURE,

  /** A call to the map's API moved the camera, such as one an overlay control made. */
  PROGRAMMATIC,
}
