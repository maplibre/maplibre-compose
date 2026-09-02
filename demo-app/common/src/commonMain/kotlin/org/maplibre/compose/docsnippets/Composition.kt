@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberDefaultMapRuntime
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle

@Composable
fun Composition() {
  // #region base-plus-content
  val runtime = rememberDefaultMapRuntime()
  val state =
    rememberMapState(
      runtime = runtime,
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
    ) {
      // Sources and layers declared here are added to the base style.
    }
  MaplibreMap(state)
  // #endregion base-plus-content
}
