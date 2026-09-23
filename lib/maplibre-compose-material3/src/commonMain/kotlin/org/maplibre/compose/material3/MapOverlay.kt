package org.maplibre.compose.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.overlay.LocalViewportInsets
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MaplibreLogo
import org.maplibre.compose.overlay.include

private val Material3AttributionOnlyOverlay = MapOverlay {
  DefaultControls {
    MaplibreLogo(Modifier.align(Alignment.BottomStart))
    ExpandingAttributionButton(Modifier.align(Alignment.BottomEnd))
  }
}

private val Material3DefaultOverlay = MapOverlay {
  val mapState = checkNotNull(LocalMapState.current)
  DefaultControls {
    DisappearingScaleBar(
      metersPerDp = { mapState.viewport?.metersPerDpAtTarget ?: 0.0 },
      zoom = { mapState.cameraPosition.zoom },
      modifier = Modifier.align(Alignment.TopStart),
    )

    DisappearingCompassButton(modifier = Modifier.align(Alignment.TopEnd))
  }
  include(Material3AttributionOnlyOverlay)
}

private val Material3FullOverlay = MapOverlay {
  include(Material3DefaultOverlay)
  DefaultControls { ZoomButtons(Modifier.align(Alignment.CenterEnd)) }
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

@Composable
private fun DefaultControls(content: @Composable BoxScope.() -> Unit) {
  val padding = LocalViewportInsets.current
  Box(
    Modifier.fillMaxSize()
      .padding(padding)
      .consumeWindowInsets(padding)
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .padding(MapOverlay.Spacing),
    content = content,
  )
}
