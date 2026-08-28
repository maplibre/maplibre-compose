@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle

@Composable
@OptIn(ExperimentalResourceApi::class)
fun Styling() {
  // #region simple
  MaplibreMap(
    state =
      rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"))
  )
  // #endregion simple

  // #region dynamic
  val variant = if (isSystemInDarkTheme()) "dark" else "light"
  MaplibreMap(
    state =
      rememberMapState(
        baseStyle = BaseStyle.Uri("https://api.protomaps.com/styles/v4/$variant/en.json?key=MY_KEY")
      )
  )
  // #endregion dynamic

  // #region local
  MaplibreMap(state = rememberMapState(baseStyle = BaseStyle.Uri(Res.getUri("files/style.json"))))
  // #endregion local
}
