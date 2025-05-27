package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.layer.RasterLayer
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.compose.source.rememberRasterSource
import dev.sargunv.maplibrecompose.core.source.TileSetOptions
import dev.sargunv.maplibrecompose.demoapp.Demo
import dev.sargunv.maplibrecompose.demoapp.DemoMapControls
import dev.sargunv.maplibrecompose.demoapp.DemoOrnamentSettings
import dev.sargunv.maplibrecompose.demoapp.DemoScaffold
import dev.sargunv.maplibrecompose.demoapp.generated.Res
import dev.sargunv.maplibrecompose.material3.controls.ScaleBarMeasure
import dev.sargunv.maplibrecompose.material3.controls.ScaleBarMeasures
import io.github.kevincianfarini.alchemist.scalar.kilometers
import io.github.kevincianfarini.alchemist.unit.LengthUnit

object LocalTilesDemo : Demo {
  override val name = "Local Tiles"
  override val description = "Display a fictional map using local tile assets."

  @Composable
  override fun Component(navigateUp: () -> Unit) {
    DemoScaffold(this, navigateUp) {
      Column {
        val cameraState = rememberCameraState()
        val styleState = rememberStyleState()

        Box(modifier = Modifier.Companion.weight(1f)) {
          MaplibreMap(
            styleUri = Res.getUri("files/styles/empty.json"),
            zoomRange = 0f..4f,
            cameraState = cameraState,
            styleState = styleState,
            ornamentSettings = DemoOrnamentSettings(),
          ) {
            val tiles =
              rememberRasterSource(
                id = "fantasy-map",
                tileSize = 256,
                tiles =
                  listOf(
                    Res.getUri("files/data/fantasy-map/0/0-0-fs8.png")
                      .replace("0/0-0", "{z}/{x}-{y}")
                  ),
                options =
                  TileSetOptions(
                    minZoom = 0,
                    maxZoom = 4,
                    attributionHtml =
                      """<a href="https://watabou.github.io/realm.html">Procgen Arena</a>""",
                  ),
              )

            RasterLayer(id = "fantasy-map", source = tiles)
          }
          DemoMapControls(
            cameraState,
            styleState,
            scaleBarMeasures = ScaleBarMeasures(LeaguesMeasure),
          )
        }
      }
    }
  }
}

data object League : LengthUnit {
  // roman league (2.22km) scaled 20x to better fit the map data
  override val nanometerScale = (2.22.kilometers * 20).toLong(LengthUnit.International.Nanometer)
  override val symbol = "lea"
}

data object LeaguesMeasure : ScaleBarMeasure.Default(League)
