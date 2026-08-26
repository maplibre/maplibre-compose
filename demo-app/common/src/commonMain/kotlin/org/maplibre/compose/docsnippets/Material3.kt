@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.material3.CompassButton
import org.maplibre.compose.material3.ExpandingAttributionButton
import org.maplibre.compose.material3.Material3
import org.maplibre.compose.material3.ScaleBar
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MaplibreLogo
import org.maplibre.compose.overlay.include

@Composable
fun Material3() {
  // #region overlay
  MaplibreMap(rememberMapState(), overlay = { include(MapOverlay.Material3) })
  // #endregion overlay

  // #region controls
  MaplibreMap(
    rememberMapState(),
    overlay = {
      ScaleBar(
        state.viewport?.metersPerDpAtTarget ?: 0.0,
        modifier = Modifier.align(Alignment.TopStart),
      ) // (1)!
      CompassButton(state, modifier = Modifier.align(Alignment.TopEnd))
      MaplibreLogo(Modifier.align(Alignment.BottomStart))
      ExpandingAttributionButton(
        state = state,
        modifier = Modifier.align(Alignment.BottomEnd),
        contentAlignment = Alignment.BottomEnd,
      )
    },
  )
  // #endregion controls
}
