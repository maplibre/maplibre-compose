@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle

@Composable
@OptIn(ExperimentalResourceApi::class)
fun Styling() {
  // -8<- [start:simple]
  MaplibreMap(baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"))
  // -8<- [end:simple]

  // -8<- [start:dynamic]
  val variant = if (isSystemInDarkTheme()) "dark" else "light"
  MaplibreMap(
    baseStyle = BaseStyle.Uri("https://api.protomaps.com/styles/v4/$variant/en.json?key=MY_KEY")
  )
  // -8<- [end:dynamic]

  // -8<- [start:local]
  MaplibreMap(baseStyle = BaseStyle.Uri(Res.getUri("files/style.json")))
  // -8<- [end:local]
}
