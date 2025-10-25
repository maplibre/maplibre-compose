package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

@Immutable
public actual data class GestureOptions(
  val isTouchPitchEnabled: Boolean = true,
  val isDragRotateEnabled: Boolean = true,
  val isDragPanEnabled: Boolean = true,
  val isDoubleClickZoomEnabled: Boolean = true,
  val isScrollZoomEnabled: Boolean = true,
  val touchZoomRotateMode: ZoomRotateMode = ZoomRotateMode.RotateAndZoom,
  val keyboardZoomRotateMode: ZoomRotateMode = ZoomRotateMode.RotateAndZoom,
) {
  public actual companion object Companion {
    public actual val Standard: GestureOptions = GestureOptions()

    public actual val PositionLocked: GestureOptions = GestureOptions(isDragPanEnabled = false)

    public actual val RotationLocked: GestureOptions =
      GestureOptions(
        isDragRotateEnabled = false,
        isTouchPitchEnabled = false,
        touchZoomRotateMode = ZoomRotateMode.ZoomOnly,
        keyboardZoomRotateMode = ZoomRotateMode.ZoomOnly,
      )

    public actual val ZoomOnly: GestureOptions =
      GestureOptions(
        isDragPanEnabled = false,
        isDragRotateEnabled = false,
        isTouchPitchEnabled = false,
        touchZoomRotateMode = ZoomRotateMode.ZoomOnly,
        keyboardZoomRotateMode = ZoomRotateMode.ZoomOnly,
      )

    public actual val AllDisabled: GestureOptions =
      GestureOptions(
        isTouchPitchEnabled = false,
        isDragRotateEnabled = false,
        isDragPanEnabled = false,
        isDoubleClickZoomEnabled = false,
        isScrollZoomEnabled = false,
        touchZoomRotateMode = ZoomRotateMode.Disabled,
        keyboardZoomRotateMode = ZoomRotateMode.Disabled,
      )
  }

  public enum class ZoomRotateMode {
    Disabled,
    ZoomOnly,
    RotateAndZoom,
  }
}
