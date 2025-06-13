package dev.sargunv.maplibrecompose.core

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
