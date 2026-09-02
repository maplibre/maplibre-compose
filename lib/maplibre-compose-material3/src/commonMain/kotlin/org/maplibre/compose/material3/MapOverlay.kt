package org.maplibre.compose.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MaplibreLogo

private val Material3Overlay = MapOverlay {
  DisappearingScaleBar(
    metersPerDp = presentation?.viewport?.metersPerDpAtTarget ?: 0.0,
    zoom = mapState.cameraPosition.zoom,
    modifier = Modifier.align(Alignment.TopStart),
  )

  DisappearingCompassButton(modifier = Modifier.align(Alignment.TopEnd))

  val overlayScope = this
  Row(
    Modifier.align(Alignment.BottomStart).fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    MaplibreLogo()
    overlayScope.ExpandingAttributionButton()
  }
}

/**
 * Applies the Material 3 color scheme and typography to the controls from [MapOverlay.Default].
 *
 * The Material 3 theme does not change the MapLibre logo colors.
 */
public val MapOverlay.Companion.Material3: MapOverlay
  get() = Material3Overlay
