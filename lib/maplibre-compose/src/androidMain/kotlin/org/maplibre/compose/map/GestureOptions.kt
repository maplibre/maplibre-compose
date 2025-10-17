package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * @param isRotateEnabled Set whether the user may rotate the map by moving two fingers in a
 *   circular motion.
 * @param isScrollEnabled Set whether the user may scroll the map by dragging or swiping with one
 *   finger.
 * @param isTiltEnabled Set whether the user may tilt the map by vertically dragging two fingers.
 * @param isZoomEnabled Set whether the user may zoom the map in and out by pinching two fingers.
 * @param isDoubleTapEnabled Set whether the user may zoom the map in by double tapping.
 * @param isQuickZoomEnabled Set whether the user may zoom the map in and out by double tapping,
 *   holding, and moving the finger up and down.
 */
@Immutable
public actual data class GestureOptions(
  val isRotateEnabled: Boolean = true,
  val isScrollEnabled: Boolean = true,
  val isTiltEnabled: Boolean = true,
  val isZoomEnabled: Boolean = true,
  val isDoubleTapEnabled: Boolean = true,
  val isQuickZoomEnabled: Boolean = true,
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
        isDoubleTapEnabled = false,
        isQuickZoomEnabled = false,
      )
  }
}
