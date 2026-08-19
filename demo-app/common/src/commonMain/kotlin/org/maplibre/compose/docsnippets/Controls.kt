@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.overlay.ExpandingAttributionButton
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MaplibreLogo
import org.maplibre.compose.overlay.include
import org.maplibre.spatialk.geojson.Position

@Composable
fun Controls() {
  // #region default
  MaplibreMap()
  // #endregion default

  // #region disabled
  MaplibreMap(overlay = MapOverlay.None)
  // #endregion disabled

  // #region custom
  MaplibreMap(
    overlay =
      MapOverlay {
        MaplibreLogo(Modifier.align(Alignment.BottomStart))
        ExpandingAttributionButton(
          cameraState = cameraState, // (1)!
          styleState = styleState,
          modifier = Modifier.align(Alignment.TopEnd),
          contentAlignment = Alignment.TopEnd,
        )
      }
  )
  // #endregion custom

  // #region insets
  MaplibreMap(
    contentWindowInsets = WindowInsets.safeDrawing.union(WindowInsets(bottom = 128.dp)) // (1)!
  )
  // #endregion insets
}

@Composable
fun LocationOverlay(position: Position) {
  // #region placedAt
  MaplibreMap(
    overlay =
      MapOverlay {
        include(MapOverlay.Default)
        Text(
          "Next sailing 12:40",
          Modifier.placedAt(position, Alignment.BottomCenter).padding(bottom = 8.dp), // (1)!
        )
      }
  )
  // #endregion placedAt
}
