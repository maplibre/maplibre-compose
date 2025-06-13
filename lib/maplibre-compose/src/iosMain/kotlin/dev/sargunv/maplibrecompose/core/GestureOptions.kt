package dev.sargunv.maplibrecompose.core

import kotlin.Boolean

public actual data class GestureOptions(
  public val isRotateEnabled: Boolean = true,
  public val isScrollEnabled: Boolean = true,
  public val isTiltEnabled: Boolean = true,
  public val isZoomEnabled: Boolean = true,
) {
  public actual companion object Companion {
    public actual val Standard: GestureOptions = GestureOptions()

    public actual val AllDisabled: GestureOptions =
      GestureOptions(
        isRotateEnabled = false,
        isScrollEnabled = false,
        isTiltEnabled = false,
        isZoomEnabled = false,
      )
  }
}
