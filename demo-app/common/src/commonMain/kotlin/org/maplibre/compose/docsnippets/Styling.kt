@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle

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

  // #region local
  val local = rememberMapState(baseStyle = BaseStyle.Uri(Res.getUri("files/style.json")))
  MaplibreMap(state = local)
  // #endregion local
}

@Composable
fun GlobalStateStyle() {
  // #region global-state
  val state =
    rememberMapState(
      baseStyle =
        BaseStyle.Json(
          """
          {"version":8,"state":{"color":{"default":"red"}},"sources":{},"layers":[]}
          """
            .trimIndent()
        )
    ) {
      BackgroundLayer(
        id = "background",
        color = globalState("color").convertToColor(const(Color.Red)),
      )
    }
  MaplibreMap(state = state) {
    Column {
      Button(
        enabled = state.style.loadState == StyleLoadState.Ready,
        onClick = { state.style.globalState.setProperty("color", JsonPrimitive("blue")) },
      ) {
        Text("Blue")
      }
      Button(
        enabled = state.style.loadState == StyleLoadState.Ready,
        onClick = { state.style.globalState.resetProperty("color") },
      ) {
        Text("Style default")
      }
    }
  }
  // #endregion global-state
}
