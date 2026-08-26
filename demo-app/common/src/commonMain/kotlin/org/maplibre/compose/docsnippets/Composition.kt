@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle

@Composable
fun Composition() {
  // #region base-plus-content
  val map =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")) {
      // Sources and layers declared here are added to the base style.
    }
  MaplibreMap(map)
  // #endregion base-plus-content
}
