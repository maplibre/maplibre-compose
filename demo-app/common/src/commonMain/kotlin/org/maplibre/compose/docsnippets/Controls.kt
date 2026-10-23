@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.filter_center_focus_24px
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.ExpandingAttributionButton
import org.maplibre.compose.overlay.GeographicLayout
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MaplibreLogo
import org.maplibre.compose.overlay.PointerPinButton
import org.maplibre.compose.overlay.include
import org.maplibre.spatialk.geojson.Position

@Composable
fun Controls() {
  // #region default
  MaplibreMap()
  // #endregion default

  // #region preset
  MaplibreMap { include(MapOverlay.Full) }
  // #endregion preset

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
  val panelHeight = 160.dp
  Box(Modifier.fillMaxSize()) {
    MaplibreMap(viewportInsets = PaddingValues(bottom = panelHeight))
    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(panelHeight)) {
      Text("Ferry schedule", Modifier.padding(16.dp))
    }
  }
  // #endregion insets
}

// #region map-context
@Composable
fun ZoomLabel(modifier: Modifier = Modifier) {
  val mapState = checkNotNull(LocalMapState.current) { "ZoomLabel must be inside a map overlay" }
  Text("Zoom ${mapState.cameraPosition.zoom.roundToInt()}", modifier)
}

@Composable
fun MapWithZoomLabel() {
  MaplibreMap {
    include(MapOverlay.Default)
    ZoomLabel(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(8.dp))
  }
}

// #endregion map-context

@Composable
fun LocationOverlay(position: Position) {
  // #region placedAt
  MaplibreMap {
    include(MapOverlay.Default)
    Surface(
      modifier =
        Modifier.placedAt(position, alignment = Alignment.BottomCenter).padding(bottom = 8.dp),
      shape = RoundedCornerShape(8.dp),
    ) {
      Text("Next sailing 12:40", Modifier.padding(8.dp))
    }
  }
  // #endregion placedAt
}

@Composable
fun OffScreenIndicator(position: Position) {
  // #region placedTowards
  val mapState = rememberMapState()
  val scope = rememberCoroutineScope()
  MaplibreMap(state = mapState) {
    include(MapOverlay.Default)
    GeographicLayout(Modifier.safeDrawingPadding().padding(8.dp)) {
      PointerPinButton(
        targetPosition = position,
        onClick = { scope.launch { mapState.animateCamera(CameraUpdate(center = position)) } },
      ) {
        Icon(
          painterResource(Res.drawable.filter_center_focus_24px),
          contentDescription = "Show the ferry terminal",
        )
      }
    }
  }
  // #endregion placedTowards
}
