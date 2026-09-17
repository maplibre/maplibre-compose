package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource

@Composable
internal fun FerryMapContent(network: FerrySchedule.Network, selected: String?, style: DemoStyle) {
  val routeSource = rememberGeoJsonSource(GeoJsonData.Features(network.routeLines))
  val terminalSource = rememberGeoJsonSource(GeoJsonData.Features(network.terminals))

  Anchor.Below({ it.type == "symbol" }) {
    LineLayer(
      id = "transit-routes",
      source = routeSource,
      color = feature["color"].asString().convertToColor(),
      width = const(3.dp),
      opacity = if (selected == null) const(0.8f) else const(0.2f),
    )
    if (selected != null) {
      LineLayer(
        id = "transit-route-selected",
        source = routeSource,
        filter = feature["route"] eq const(selected),
        color = feature["color"].asString().convertToColor(),
        width = const(4.dp),
      )
    }

    CircleLayer(
      id = "transit-terminals",
      source = terminalSource,
      radius = const(4.dp),
      color = const(Color.White),
      strokeWidth = const(2.dp),
      strokeColor = const(Color(0xFF37474F)),
    )
  }
  SymbolLayer(
    id = "transit-terminal-names",
    source = terminalSource,
    textField = feature["name"].asString(),
    textFont = const(style.textFont),
    textColor = const(Color(0xFF37474F)),
    textHaloColor = const(Color.White),
    textHaloWidth = const(1.dp),
    textAnchor = const(SymbolAnchor.Top),
    textOffset = textOffset(0.em, 0.4.em),
  )
}
