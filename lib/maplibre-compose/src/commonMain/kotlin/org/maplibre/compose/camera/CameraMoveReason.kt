package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable

/**
 * What started the most recent camera movement.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface CameraMoveReason {
  /** The map is detached or the camera has not moved yet. */
  public data object None : CameraMoveReason

  /** A gesture on the map moved the camera: a pan, a zoom, a rotation, or a pitch. */
  public data object Gesture : CameraMoveReason

  /**
   * Something other than a gesture moved the camera: a call to the map's API, such as one an
   * overlay control made, or a camera change that the engine started itself.
   */
  public data object Programmatic : CameraMoveReason
}

/**
 * Keeps [CameraMoveReason] open: callers' `when` needs an `else` branch. The library never reports
 * it.
 */
internal data object UnspecifiedCameraMoveReason : CameraMoveReason
