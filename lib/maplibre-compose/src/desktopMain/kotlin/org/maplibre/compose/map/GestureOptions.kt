package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

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
