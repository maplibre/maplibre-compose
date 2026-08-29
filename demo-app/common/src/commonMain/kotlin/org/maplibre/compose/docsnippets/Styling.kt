@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle

@Composable
@OptIn(ExperimentalResourceApi::class)
fun Styling() {
  // #region simple
  val simple =
    rememberMapState(
      initialBaseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty")
    )
  MaplibreMap(state = simple)
  // #endregion simple

  // #region dynamic
  val variant = if (isSystemInDarkTheme()) "dark" else "light"
  val dynamic = rememberMapState()
  SideEffect {
    dynamic.style.baseStyle =
      BaseStyle.Uri("https://api.protomaps.com/styles/v4/$variant/en.json?key=MY_KEY")
  }
  MaplibreMap(state = dynamic)
  // #endregion dynamic

  // #region local
  val local = rememberMapState(initialBaseStyle = BaseStyle.Uri(Res.getUri("files/style.json")))
  MaplibreMap(state = local)
  // #endregion local
}
