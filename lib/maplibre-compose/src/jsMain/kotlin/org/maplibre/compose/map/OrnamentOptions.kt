package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment

@Immutable
public actual data class OrnamentOptions(
  val isLogoEnabled: Boolean = true,
  val logoAlignment: Alignment = Alignment.Companion.BottomStart,
  val isAttributionEnabled: Boolean = true,
  val attributionAlignment: Alignment = Alignment.Companion.BottomEnd,
  val isNavigationEnabled: Boolean = true,
  val navigationAlignment: Alignment = Alignment.Companion.TopEnd,
  val isScaleBarEnabled: Boolean = true,
  val scaleBarAlignment: Alignment = Alignment.Companion.TopStart,
  // TODO: terrain control
  // TODO: globe control
  // TODO: fullscreen control
  // TODO: geolocate control
) {
  public actual companion object Companion {
    public actual val AllEnabled: OrnamentOptions = OrnamentOptions()
    public actual val AllDisabled: OrnamentOptions =
      OrnamentOptions(
        isLogoEnabled = false,
        isAttributionEnabled = false,
        isNavigationEnabled = false,
        isScaleBarEnabled = false,
      )

    public actual val OnlyLogo: OrnamentOptions = AllDisabled.copy(isLogoEnabled = true)
  }
}
