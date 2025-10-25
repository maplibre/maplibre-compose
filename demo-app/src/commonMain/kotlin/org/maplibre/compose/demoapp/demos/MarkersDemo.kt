package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.em
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.marker
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Feature.Companion.getStringProperty
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

object MarkersDemo : Demo {
  override val name = "Markers and icons"

  override val region =
    BoundingBox(southwest = Position(-125.0, 24.0), northeast = Position(-66.0, 49.0))

  override val mapContentVisibilityState = mutableStateOf(true)

  private var selectedFeature by mutableStateOf<Feature<Geometry, JsonObject?>?>(null)

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    val marker = painterResource(Res.drawable.marker)

    val amtrakStations =
      rememberGeoJsonSource(
        data =
          GeoJsonData.Uri(
            "https://raw.githubusercontent.com/datanews/amtrak-geojson/refs/heads/master/amtrak-stations.geojson"
          )
      )

    SymbolLayer(
      id = "amtrak-stations",
      source = amtrakStations,
      onClick = { features ->
        selectedFeature = features.firstOrNull()
        ClickResult.Consume
      },
      iconImage = image(marker),
      textField =
        format(
          span(image("railway")),
          span(" "),
          span(feature["STNCODE"].asString(), textSize = const(1.2f.em)),
        ),
      textFont = const(listOf("Noto Sans Regular")),
      textColor = const(MaterialTheme.colorScheme.onBackground),
      textOffset = offset(0.em, 0.6.em),
    )
  }

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    selectedFeature?.let { feature ->
      AlertDialog(
        onDismissRequest = { selectedFeature = null },
        confirmButton = {},
        title = { Text(feature.getStringProperty("STNNAME") ?: "") },
        text = {
          Column {
            Text("Station Code: ${feature.getStringProperty("STNCODE") ?: ""}")
            Text("Station Type: ${feature.getStringProperty("STNTYPE") ?: ""}")
            Text("Address: ${feature.getStringProperty("ADDRESS1") ?: ""}")
            Text("City: ${feature.getStringProperty("CITY") ?: ""}")
            Text("State: ${feature.getStringProperty("STATE") ?: ""}")
            Text("Zip: ${feature.getStringProperty("ZIP") ?: ""}")
          }
        },
      )
    }
  }
}
