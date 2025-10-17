package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import org.maplibre.kmp.native.map.MapControls

@Immutable
public actual data class GestureOptions(
  val isDragPanEnabled: Boolean = true,
  val isDragRotateTiltEnabled: Boolean = true,
  val isScrollZoomEnabled: Boolean = true,
  val isDoubleClickZoomEnabled: Boolean = true,
  val isKeyboardPanEnabled: Boolean = true,
  val isKeyboardZoomEnabled: Boolean = true,
) {
  public actual companion object Companion {
    public actual val Standard: GestureOptions = GestureOptions()

    public actual val PositionLocked: GestureOptions =
      GestureOptions(isDragPanEnabled = false, isKeyboardPanEnabled = false)

    public actual val RotationLocked: GestureOptions =
      GestureOptions(isDragRotateTiltEnabled = false)

    public actual val ZoomOnly: GestureOptions =
      GestureOptions(
        isDragPanEnabled = false,
        isKeyboardPanEnabled = false,
        isDragRotateTiltEnabled = false,
      )

    public actual val AllDisabled: GestureOptions =
      GestureOptions(
        isDragPanEnabled = false,
        isDragRotateTiltEnabled = false,
        isScrollZoomEnabled = false,
        isDoubleClickZoomEnabled = false,
        isKeyboardPanEnabled = false,
        isKeyboardZoomEnabled = false,
      )
  }
}

internal fun GestureOptions.toMapControlsConfig(): MapControls.Config {
  return MapControls.Config(
    enableDragPan = isDragPanEnabled,
    enableScrollZoom = isScrollZoomEnabled,
    enableDoubleClickZoom = isDoubleClickZoomEnabled,
    enableDragRotate = isDragRotateTiltEnabled,
    enableDragTilt = isDragRotateTiltEnabled,
    enableKeyboardPan = isKeyboardPanEnabled,
    enableKeyboardZoom = isKeyboardZoomEnabled,
  )
}
