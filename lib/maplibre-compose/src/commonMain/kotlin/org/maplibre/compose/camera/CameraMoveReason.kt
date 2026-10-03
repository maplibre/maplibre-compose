package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable

/** What started the most recent camera movement. */
@Immutable
public enum class CameraMoveReason {
  /** The map is detached or the camera has not moved yet. */
  None,

  /** A gesture on the map moved the camera: a pan, a zoom, a rotation, or a tilt. */
  Gesture,

  /** A call to the map's API moved the camera, such as one an overlay control made. */
  Programmatic,
}
