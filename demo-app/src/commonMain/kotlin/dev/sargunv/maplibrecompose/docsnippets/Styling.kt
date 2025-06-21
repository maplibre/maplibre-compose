@file:Suppress("unused")

package dev.sargunv.maplibrecompose.docsnippets

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.core.BaseStyle
import dev.sargunv.maplibrecompose.demoapp.generated.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

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
