package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

/**
 * @param padding Padding of the ornaments to the edge of the map.
 * @param isLogoEnabled Determines if the MapLibre logo is displayed.
 * @param logoAlignment Specifies the location of the MapLibre logo. On Android, only the four
 *   corners are supported (TopStart, TopEnd, BottomStart, BottomEnd). On iOS, the four corners,
 *   centers along the edges, and the center are supported.
 * @param isAttributionEnabled Determines if the copyright attribution button is displayed.
 * @param attributionAlignment Specifies the location of the attribution button. On Android, only
 *   the four corners are supported (TopStart, TopEnd, BottomStart, BottomEnd). On iOS, the four
 *   corners, centers along the edges, and the center are supported.
 * @param isCompassEnabled Determines if a compass is displayed to indicate north and reset the
 *   camera alignment.
 * @param compassAlignment Specifies the location of the compass. On Android, only the four corners
 *   are supported (TopStart, TopEnd, BottomStart, BottomEnd). On iOS, the four corners, centers
 *   along the edges, and the center are supported.
 * @param isScaleBarEnabled Determines if a scale bar is displayed to indicate the map scale.
 * @param scaleBarAlignment Specifies the location of the scale bar. On Android, only the four
 *   corners are supported (TopStart, TopEnd, BottomStart, BottomEnd). On iOS, the four corners,
 *   centers along the edges, and the center are supported.
 */
@Immutable
public actual data class OrnamentOptions(
  val padding: PaddingValues = PaddingValues(0.dp),
  val isLogoEnabled: Boolean = true,
  val logoAlignment: Alignment = Alignment.BottomStart,
  val isAttributionEnabled: Boolean = true,
  val attributionAlignment: Alignment = Alignment.BottomEnd,
  val isCompassEnabled: Boolean = true,
  val compassAlignment: Alignment = Alignment.TopEnd,
  val isScaleBarEnabled: Boolean = true,
  val scaleBarAlignment: Alignment = Alignment.TopStart,
) {
  public actual companion object Companion {
    public actual val AllEnabled: OrnamentOptions = OrnamentOptions()
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
