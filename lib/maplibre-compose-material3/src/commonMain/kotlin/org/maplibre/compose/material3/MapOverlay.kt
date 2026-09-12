package org.maplibre.compose.material3

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.maplibre.compose.overlay.Controls
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MaplibreLogo
import org.maplibre.compose.overlay.include

private val Material3AttributionOnlyOverlay = MapOverlay {
  Controls {
    MaplibreLogo(Modifier.align(Alignment.BottomStart))
    ExpandingAttributionButton(Modifier.align(Alignment.BottomEnd))
  }
}

private val Material3DefaultOverlay = MapOverlay {
  Controls {
    DisappearingScaleBar(
      metersPerDp = mapState.viewport?.metersPerDpAtTarget ?: 0.0,
      zoom = mapState.cameraPosition.zoom,
      modifier = Modifier.align(Alignment.TopStart),
    )

    DisappearingCompassButton(modifier = Modifier.align(Alignment.TopEnd))
  }
  include(Material3AttributionOnlyOverlay)
}

private val Material3FullOverlay = MapOverlay {
  include(Material3DefaultOverlay)
  Controls { ZoomButtons(Modifier.align(Alignment.CenterEnd)) }
}

/**
 * Applies the Material 3 color scheme and typography to the controls from
 * [MapOverlay.AttributionOnly].
 *
 * The Material 3 theme does not change the MapLibre logo colors.
 */
public val MapOverlay.Companion.Material3AttributionOnly: MapOverlay
  get() = Material3AttributionOnlyOverlay

/**
 * Applies the Material 3 color scheme and typography to the controls from [MapOverlay.Default].
 *
 * The Material 3 theme does not change the MapLibre logo colors.
 */
public val MapOverlay.Companion.Material3: MapOverlay
  get() = Material3DefaultOverlay

/**
 * Applies the Material 3 color scheme and typography to the controls from [MapOverlay.Full].
 *
 * The Material 3 theme does not change the MapLibre logo colors.
 */
public val MapOverlay.Companion.Material3Full: MapOverlay
  get() = Material3FullOverlay
