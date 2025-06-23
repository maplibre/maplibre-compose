package dev.sargunv.maplibrecompose.demoapp.demos

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
import dev.sargunv.maplibrecompose.compose.ClickResult
import dev.sargunv.maplibrecompose.compose.layer.SymbolLayer
import dev.sargunv.maplibrecompose.compose.source.rememberGeoJsonSource
import dev.sargunv.maplibrecompose.core.source.GeoJsonData
import dev.sargunv.maplibrecompose.demoapp.DemoState
import dev.sargunv.maplibrecompose.demoapp.generated.Res
import dev.sargunv.maplibrecompose.demoapp.generated.marker
import dev.sargunv.maplibrecompose.expressions.dsl.asString
import dev.sargunv.maplibrecompose.expressions.dsl.const
import dev.sargunv.maplibrecompose.expressions.dsl.feature
import dev.sargunv.maplibrecompose.expressions.dsl.format
import dev.sargunv.maplibrecompose.expressions.dsl.image
import dev.sargunv.maplibrecompose.expressions.dsl.offset
import dev.sargunv.maplibrecompose.expressions.dsl.span
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position
import org.jetbrains.compose.resources.painterResource

object MarkersDemo : Demo {
  override val name = "Markers and icons"

  override val region =
    BoundingBox(southwest = Position(-125.0, 24.0), northeast = Position(-66.0, 49.0))

  override val mapContentVisibilityState = mutableStateOf(true)

  private var selectedFeature by mutableStateOf<Feature?>(null)

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    val marker = painterResource(Res.drawable.marker)

    val amtrakStations =
      rememberGeoJsonSource(
        id = "amtrak-stations",
        data =
          GeoJsonData.Uri(
            "https://raw.githubusercontent.com/datanews/amtrak-geojson/refs/heads/master/amtrak-stations.geojson"
          ),
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
