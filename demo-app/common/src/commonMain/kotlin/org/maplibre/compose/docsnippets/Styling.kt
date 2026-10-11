@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Light
import org.maplibre.compose.style.StyleOverrides

@Composable
@OptIn(ExperimentalResourceApi::class)
fun Styling() {
  // #region simple
  val simple =
    rememberMapState(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"))
  MaplibreMap(state = simple)
  // #endregion simple

  // #region dynamic
  val variant = if (isSystemInDarkTheme()) "dark" else "light"
  val baseStyle = BaseStyle.Uri("https://api.protomaps.com/styles/v4/$variant/en.json?key=MY_KEY")
  val dynamic = rememberMapState(baseStyle = baseStyle)
  MaplibreMap(state = dynamic)
  // #endregion dynamic

  // #region overrides
  val overrides = StyleOverrides { light = Light(intensity = const(0.25f)) }
  val lit = rememberMapState(baseStyle = baseStyle, styleOverrides = overrides)
  MaplibreMap(state = lit)
  // #endregion overrides

  // #region local
  val local = rememberMapState(baseStyle = BaseStyle.Uri(Res.getUri("files/style.json")))
  MaplibreMap(state = local)
  // #endregion local
}
