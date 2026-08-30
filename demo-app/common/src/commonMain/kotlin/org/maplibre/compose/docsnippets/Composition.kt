@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleComposition

@Composable
fun Composition() {
  // #region base-plus-content
  val state =
    rememberMapState(
      initialBaseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
    )
  val composition = remember {
    StyleComposition {
      // Sources and layers declared here are added to the base style.
    }
  }
  MaplibreMap(state = state, styleComposition = composition)
  // #endregion base-plus-content
}
