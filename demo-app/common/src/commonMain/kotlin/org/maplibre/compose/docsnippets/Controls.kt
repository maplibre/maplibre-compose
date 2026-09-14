@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.overlay.ExpandingAttributionButton
import org.maplibre.compose.overlay.GeographicLayout
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MaplibreLogo
import org.maplibre.compose.overlay.include
import org.maplibre.compose.overlay.rememberPlacedTowardsState
import org.maplibre.spatialk.geojson.Position

@Composable
fun Controls() {
  // #region default
  MaplibreMap()
  // #endregion default

  // #region disabled
  MaplibreMap {}
  // #endregion disabled

  // #region custom
  MaplibreMap {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
      MaplibreLogo(Modifier.align(Alignment.BottomStart))
      ExpandingAttributionButton(
        modifier = Modifier.align(Alignment.TopEnd),
        contentAlignment = Alignment.TopEnd,
      )
    }
  }
  // #endregion custom

  // #region insets
  val mapInsets = WindowInsets.safeDrawing.union(WindowInsets(bottom = 128.dp)) // (1)!
  MaplibreMap(cameraPadding = mapInsets.asPaddingValues())
  // #endregion insets
}

@Composable
fun LocationOverlay(position: Position) {
  // #region placedAt
  MaplibreMap {
    include(MapOverlay.Default)
    Text(
      "Next sailing 12:40",
      Modifier.placedAt(position, alignment = Alignment.BottomCenter)
        .padding(bottom = 8.dp), // (1)!
    )
  }
  // #endregion placedAt
}

@Composable
fun OffScreenIndicator(position: Position) {
  // #region placedTowards
  MaplibreMap {
    include(MapOverlay.Default)
    GeographicLayout(Modifier.safeDrawingPadding().padding(8.dp)) {
      val placement = rememberPlacedTowardsState() // (1)!
      Text(
        "▲",
        Modifier.placedTowards(position, state = placement).graphicsLayer {
          rotationZ = placement.angleDegrees // (2)!
        },
      )
    }
  }
  // #endregion placedTowards
}
