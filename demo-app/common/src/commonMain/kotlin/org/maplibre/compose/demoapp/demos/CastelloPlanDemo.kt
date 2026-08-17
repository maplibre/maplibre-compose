package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.OpenFreeMap
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.rememberImageSource
import org.maplibre.compose.util.PositionQuad
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

object CastelloPlanDemo : Demo {
  override val name = "Castello Plan"
  override val description = "The 1660 map of New Amsterdam draped over lower Manhattan."
  override val region = BoundingBox(west = -74.019, south = 40.699, east = -74.004, north = 40.710)
  override val preferredStyle = OpenFreeMap.Liberty

  override val camera =
    CameraPosition(target = Position(longitude = -74.0115, latitude = 40.7045), zoom = 15.2)

  private var opacity by mutableFloatStateOf(0.7f)

  // The plan is drawn with north to the right, so the image's left edge is the Battery and its
  // right edge is the wall at Wall Street. The corners are hand-tuned for rough landmark alignment.
  private val corners =
    PositionQuad(
      topLeft = Position(longitude = -74.0173, latitude = 40.7010),
      topRight = Position(longitude = -74.0143, latitude = 40.7088),
      bottomRight = Position(longitude = -74.0063, latitude = 40.7063),
      bottomLeft = Position(longitude = -74.0093, latitude = 40.6985),
    )

  @Composable
  override fun MapContent() {
    val source =
      rememberImageSource(position = corners, uri = Res.getUri("files/castello-plan.jpg"))
    RasterLayer(id = "castello-plan", source = source, opacity = const(opacity))
  }

  @Composable
  override fun Panel() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
      Text("Overlay opacity", style = MaterialTheme.typography.bodyLarge)
      Slider(value = opacity, onValueChange = { opacity = it })
    }
  }
}
