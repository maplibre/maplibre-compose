package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment

@Immutable
public actual data class OrnamentOptions(
  val isLogoEnabled: Boolean = true,
  val logoAlignment: Alignment = Alignment.BottomStart,
  val isAttributionEnabled: Boolean = true,
  val attributionAlignment: Alignment = Alignment.BottomEnd,
  val isNavigationEnabled: Boolean = true,
  val navigationAlignment: Alignment = Alignment.TopEnd,
  val isScaleBarEnabled: Boolean = true,
  val scaleBarAlignment: Alignment = Alignment.TopStart,
  val isGlobeButtonEnabled: Boolean = true,
  val globeButtonAlignment: Alignment = Alignment.TopEnd,
  val isFullscreenButtonEnabled: Boolean = true,
  val fullscreenButtonAlignment: Alignment = Alignment.BottomEnd,
  val isGeolocateButtonEnabled: Boolean = true,
  val geolocateButtonAlignment: Alignment = Alignment.TopEnd,
  // TODO terrain control
) {
  public actual companion object Companion {
    public actual val AllEnabled: OrnamentOptions = OrnamentOptions()
    public actual val AllDisabled: OrnamentOptions =
      OrnamentOptions(
        isLogoEnabled = false,
        isAttributionEnabled = false,
        isNavigationEnabled = false,
        isScaleBarEnabled = false,
        isGlobeButtonEnabled = false,
        isFullscreenButtonEnabled = false,
        isGeolocateButtonEnabled = false,
      )

    public actual val OnlyLogo: OrnamentOptions = AllDisabled.copy(isLogoEnabled = true)
  }
}
