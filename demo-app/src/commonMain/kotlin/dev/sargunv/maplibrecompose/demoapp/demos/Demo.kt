package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import dev.sargunv.maplibrecompose.compose.MaplibreComposable
import dev.sargunv.maplibrecompose.demoapp.DemoState
import io.github.dellisd.spatialk.geojson.BoundingBox

interface Demo {
  val name: String

  val region: BoundingBox?
    get() = null

  val mapContentVisibilityState: MutableState<Boolean>?
    get() = null

  @MaplibreComposable @Composable fun MapContent(state: DemoState, isOpen: Boolean) {}

  @UiComposable @Composable fun SheetContent(state: DemoState, modifier: Modifier) {}
}
