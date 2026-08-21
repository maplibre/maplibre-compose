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
    metersPerDp = cameraState.viewport?.metersPerDpAtTarget ?: 0.0,
    zoom = cameraState.position.zoom,
    modifier = Modifier.align(Alignment.TopStart),
  )

  DisappearingCompassButton(cameraState = cameraState, modifier = Modifier.align(Alignment.TopEnd))

  // Read before entering the Row, whose scope shadows this one.
  val camera = cameraState
  val style = styleState
  Row(
    Modifier.align(Alignment.BottomStart).fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    MaplibreLogo()
    ExpandingAttributionButton(cameraState = camera, styleState = style)
  }
}

/**
 * The controls of [MapOverlay.Default], themed from the Material 3 color scheme and typography.
 *
 * The MapLibre logo is the same either way, because its artwork carries its own colors.
 */
public val MapOverlay.Companion.Material3: MapOverlay
  get() = Material3Overlay
