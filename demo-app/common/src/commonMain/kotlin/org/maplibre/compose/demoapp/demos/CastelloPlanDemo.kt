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
  override val region = BoundingBox(west = -74.018, south = 40.7005, east = -74.006, north = 40.710)
  override val preferredStyle = OpenFreeMap.Liberty

  override val camera =
    CameraPosition(target = Position(longitude = -74.0119, latitude = 40.7053), zoom = 15.5)

  private var opacity by mutableFloatStateOf(0.7f)

  // North is to the image's upper right: the Hudson runs along the top edge, the wall at Wall
  // Street down the right edge, and the East River shore along the bottom. The corners come from a
  // similarity fit over four landmarks (the fort, both wall gates, and the canal mouth), so the
  // 1660 shoreline lands inland of today's landfill coast.
  private val corners =
    PositionQuad(
      topLeft = Position(longitude = -74.0178, latitude = 40.7040),
      topRight = Position(longitude = -74.0118, latitude = 40.7100),
      bottomRight = Position(longitude = -74.0060, latitude = 40.7067),
      bottomLeft = Position(longitude = -74.0120, latitude = 40.7006),
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
