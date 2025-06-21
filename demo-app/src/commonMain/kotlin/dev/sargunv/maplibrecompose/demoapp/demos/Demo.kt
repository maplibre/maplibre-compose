package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sargunv.maplibrecompose.demoapp.DemoState
import io.github.dellisd.spatialk.geojson.BoundingBox

interface Demo {
  val name: String
  val region: BoundingBox?
    get() = null

  @Composable fun MapContent(state: DemoState, isOpen: Boolean) {}

  @Composable fun SheetContent(state: DemoState, modifier: Modifier) {}
}
