@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState

// #region app
@Composable
fun MyApp() {
  MaplibreMap(rememberMapState())
}
// #endregion app
