package dev.sargunv.maplibrecompose.core

import androidx.compose.ui.Alignment

public actual data class OrnamentOptions(
  val isLogoEnabled: Boolean = true,
  val logoAlignment: Alignment = Alignment.Companion.BottomStart,
  val isAttributionEnabled: Boolean = true,
  val attributionAlignment: Alignment = Alignment.Companion.BottomEnd,
  val isCompassEnabled: Boolean = true,
  val compassAlignment: Alignment = Alignment.Companion.TopEnd,
  val isScaleBarEnabled: Boolean = true,
  val scaleBarAlignment: Alignment = Alignment.Companion.TopStart,
) {
  public actual companion object Companion {
    public actual val Standard: OrnamentOptions = OrnamentOptions()

    public actual val AllDisabled: OrnamentOptions =
      OrnamentOptions(
        isLogoEnabled = false,
        isAttributionEnabled = false,
        isCompassEnabled = false,
        isScaleBarEnabled = false,
      )

    public actual val OnlyLogo: OrnamentOptions = AllDisabled.copy(isLogoEnabled = true)
  }
}
