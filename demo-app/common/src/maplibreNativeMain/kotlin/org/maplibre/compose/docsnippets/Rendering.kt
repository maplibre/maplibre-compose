@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import org.maplibre.compose.map.CameraProjection
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions

@Composable
fun AxonometricMap() {
  // #region camera-projection
  MaplibreMap(
    renderOptions =
      RenderOptions {
        cameraProjection = CameraProjection.Axonometric()
      }
  )
  // #endregion camera-projection
}
