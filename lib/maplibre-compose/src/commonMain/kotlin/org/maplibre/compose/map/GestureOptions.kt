package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * Configures platform-specific map interaction gesture options.
 *
 * The companion object provides some presets available from common code, but fine-grained
 * customization on multiple platforms requires configuring these options in expect/actual code.
 */
@Immutable
public expect class GestureOptions {
  public companion object Companion {
    /** The recommended configuration for most use cases. */
    public val Standard: GestureOptions

    /** Disable gestures for moving the camera position. Rotation, tilt and zoom are allowed */
    public val PositionLocked: GestureOptions

    /** Disable gestures for rotation and tilt. Moving position and zoom are allowed */
    public val RotationLocked: GestureOptions

    /** Disable all gestures except for zoom. */
    public val ZoomOnly: GestureOptions

    /** All gestures disabled. Useful if you want to have full control over the camera movement. */
    public val AllDisabled: GestureOptions
  }
}
