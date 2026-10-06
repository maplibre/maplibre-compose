package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/**
 * What started the most recent camera movement.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
@JvmInline
public value class CameraMoveReason private constructor(private val name: String) {
  override fun toString(): String = name

  public companion object {
    /** The map is detached or the camera has not moved yet. */
    public val None: CameraMoveReason = CameraMoveReason("None")

    /** A gesture on the map moved the camera: a pan, a zoom, a rotation, or a pitch. */
    public val Gesture: CameraMoveReason = CameraMoveReason("Gesture")

    /**
     * Something other than a gesture moved the camera: a call to the map's API, such as one an
     * overlay control made, or a camera change that the engine started itself.
     */
    public val Programmatic: CameraMoveReason = CameraMoveReason("Programmatic")
  }
}
