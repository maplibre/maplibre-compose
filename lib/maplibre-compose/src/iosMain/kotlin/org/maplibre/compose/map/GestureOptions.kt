package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import kotlin.Boolean

/**
 * @param isRotateEnabled Set whether the user may rotate the map by moving two fingers in a
 *   circular motion.
 * @param isScrollEnabled Set whether the user may scroll the map by dragging or swiping with one
 *   finger.
 * @param isTiltEnabled Set whether the user may tilt the map by vertically dragging two fingers.
 * @param isZoomEnabled Set whether the user may zoom the map in and out by pinching two fingers or
 *   by double tapping, holding, and moving the finger up and down.
 * @param isHapticFeedbackEnabled Set whether the user receives haptic feedback when rotating the
 *   map to due north.
 */
@Immutable
public actual data class GestureOptions(
  public val isRotateEnabled: Boolean = true,
  public val isScrollEnabled: Boolean = true,
  public val isTiltEnabled: Boolean = true,
  public val isZoomEnabled: Boolean = true,
  public val isHapticFeedbackEnabled: Boolean = true,
) {
  public actual companion object Companion {
    public actual val Standard: GestureOptions = GestureOptions()

    public actual val PositionLocked: GestureOptions = GestureOptions(isScrollEnabled = false)

    public actual val RotationLocked: GestureOptions =
      GestureOptions(isRotateEnabled = false, isTiltEnabled = false)

    public actual val ZoomOnly: GestureOptions =
      GestureOptions(isScrollEnabled = false, isRotateEnabled = false, isTiltEnabled = false)

    public actual val AllDisabled: GestureOptions =
      GestureOptions(
        isRotateEnabled = false,
        isScrollEnabled = false,
        isTiltEnabled = false,
        isZoomEnabled = false,
        isHapticFeedbackEnabled = false,
      )
  }
}
