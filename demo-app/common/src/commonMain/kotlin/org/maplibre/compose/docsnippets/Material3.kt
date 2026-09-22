@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.material3.CompassButton
import org.maplibre.compose.material3.ExpandingAttributionButton
import org.maplibre.compose.material3.Material3
import org.maplibre.compose.material3.ScaleBar
import org.maplibre.compose.material3.ZoomButtons
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MaplibreLogo
import org.maplibre.compose.overlay.include

@Composable
fun Material3() {
  // #region overlay
  MaplibreMap { include(MapOverlay.Material3) }
  // #endregion overlay

  // #region controls
  MaplibreMap {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
      val mapState = checkNotNull(LocalMapState.current)
      ScaleBar(
        metersPerDp = { mapState.cameraPosition.metersPerDp }, // (1)!
        modifier = Modifier.align(Alignment.TopStart),
      ) // (2)!
      CompassButton(modifier = Modifier.align(Alignment.TopEnd))
      ZoomButtons(Modifier.align(Alignment.CenterEnd))
      MaplibreLogo(Modifier.align(Alignment.BottomStart))
      ExpandingAttributionButton(
        modifier = Modifier.align(Alignment.BottomEnd),
        contentAlignment = Alignment.BottomEnd,
      )
    }
  }
  // #endregion controls
}
