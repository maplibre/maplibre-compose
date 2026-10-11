@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle

@Composable
fun Styling() {
  // #region simple
  val simple =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"))
  MaplibreMap(state = simple)
  // #endregion simple

  // #region dynamic
  val flavor = if (isSystemInDarkTheme()) "dark" else "light"
  val themed =
    rememberMapState(
      baseStyle =
        BaseStyle.Uri("https://api.protomaps.com/styles/v5/$flavor/en.json?key=MY_API_KEY")
    )
  MaplibreMap(state = themed)
  // #endregion dynamic

  // #region local
  val local = rememberMapState(baseStyle = BaseStyle.Uri(Res.getUri("files/styles/colorful.json")))
  MaplibreMap(state = local)
  // #endregion local
}
