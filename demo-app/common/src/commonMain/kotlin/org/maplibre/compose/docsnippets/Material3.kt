@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.material3.CompassButton
import org.maplibre.compose.material3.DisappearingCompassButton
import org.maplibre.compose.material3.DisappearingScaleBar
import org.maplibre.compose.material3.ExpandingAttributionButton
import org.maplibre.compose.material3.ScaleBar
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MaplibreLogo

@Composable
fun Material3() {
  // #region controls
  MaplibreMap(
    overlay =
      MapOverlay {
        MaplibreLogo(Modifier.align(Alignment.BottomStart))
        ScaleBar(cameraState.metersPerDpAtTarget, modifier = Modifier.align(Alignment.TopStart))
        CompassButton(cameraState, modifier = Modifier.align(Alignment.TopEnd))
        ExpandingAttributionButton(
          cameraState = cameraState,
          styleState = styleState,
          modifier = Modifier.align(Alignment.BottomEnd),
          contentAlignment = Alignment.BottomEnd,
        )
      }
  )
  // #endregion controls

  // #region disappearing-controls
  MaplibreMap(
    overlay =
      MapOverlay {
        MaplibreLogo(Modifier.align(Alignment.BottomStart))
        DisappearingScaleBar(
          metersPerDp = cameraState.metersPerDpAtTarget,
          zoom = cameraState.position.zoom,
          modifier = Modifier.align(Alignment.TopStart),
        ) // (1)!
        DisappearingCompassButton(cameraState, modifier = Modifier.align(Alignment.TopEnd)) // (2)!
        ExpandingAttributionButton(
          cameraState = cameraState,
          styleState = styleState,
          modifier = Modifier.align(Alignment.BottomEnd),
          contentAlignment = Alignment.BottomEnd,
        )
      }
  )
  // #endregion disappearing-controls
}
