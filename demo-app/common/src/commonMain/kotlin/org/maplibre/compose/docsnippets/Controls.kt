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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.ExpandingAttributionButton
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MaplibreLogo
import org.maplibre.compose.overlay.include
import org.maplibre.compose.overlay.rememberPlacedTowardsState
import org.maplibre.spatialk.geojson.Position

@Composable
fun Controls() {
  // #region default
  MaplibreMap(rememberMapState())
  // #endregion default

  // #region disabled
  MaplibreMap(rememberMapState(), overlay = {})
  // #endregion disabled

  // #region custom
  MaplibreMap(
    rememberMapState(),
    overlay = {
      MaplibreLogo(Modifier.align(Alignment.BottomStart))
      ExpandingAttributionButton( // (1)!
        modifier = Modifier.align(Alignment.TopEnd),
        contentAlignment = Alignment.TopEnd,
      )
    },
  )
  // #endregion custom

  // #region insets
  val mapInsets = WindowInsets.safeDrawing.union(WindowInsets(bottom = 128.dp))
  MaplibreMap(
    rememberMapState(),
    contentWindowInsets = mapInsets, // (1)!
  )
  // #endregion insets
}

@Composable
fun LocationOverlay(position: Position) {
  // #region placedAt
  MaplibreMap(
    rememberMapState(),
    overlay = {
      include(MapOverlay.Default)
      Text(
        "Next sailing 12:40",
        Modifier.placedAt(position, Alignment.BottomCenter).padding(bottom = 8.dp), // (1)!
      )
    },
  )
  // #endregion placedAt
}

@Composable
fun OffScreenIndicator(position: Position) {
  // #region placedTowards
  MaplibreMap(
    rememberMapState(),
    overlay = {
      include(MapOverlay.Default)
      val placement = rememberPlacedTowardsState() // (1)!
      Text(
        "▲",
        Modifier.placedTowards(position, placement).graphicsLayer {
          rotationZ = placement.angleDegrees // (2)!
        },
      )
    },
  )
  // #endregion placedTowards
}
