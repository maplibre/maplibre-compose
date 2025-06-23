package dev.sargunv.maplibrecompose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.StyleState
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.core.RenderOptions
import dev.sargunv.maplibrecompose.demoapp.demos.AnimatedLayerDemo
import dev.sargunv.maplibrecompose.demoapp.demos.CameraStateDemo
import dev.sargunv.maplibrecompose.demoapp.demos.ClusteredPointsDemo
import dev.sargunv.maplibrecompose.demoapp.demos.Demo
import dev.sargunv.maplibrecompose.demoapp.demos.MarkersDemo
import dev.sargunv.maplibrecompose.demoapp.demos.StyleSelectorDemo
import dev.sargunv.maplibrecompose.demoapp.util.Platform

class DemoState(
  val nav: NavHostController,
  val cameraState: CameraState,
  val styleState: StyleState,
) {
  // TODO:
  // Camera follow
  // Image source
  // User location
  val demos =
    (listOf(
      StyleSelectorDemo,
      CameraStateDemo,
      AnimatedLayerDemo,
      MarkersDemo,
      ClusteredPointsDemo,
    ) + Platform.extraDemos)

  var selectedStyle by mutableStateOf<DemoStyle>(Protomaps.Light)
  var renderOptions by mutableStateOf(RenderOptions.Standard)

  private val navDestinationState = mutableStateOf<NavDestination?>(null)

  val navDestination: NavDestination?
    get() = navDestinationState.value

  init {
    nav.addOnDestinationChangedListener { _, destination, _ ->
      navDestinationState.value = destination
    }
  }

  fun isDemoOpen(demo: Demo): Boolean {
    return navDestination?.route == demo.name
  }

  fun shouldRenderMapContent(demo: Demo): Boolean {
    return isDemoOpen(demo) || demo.mapContentVisibilityState?.value ?: false
  }
}

@Composable
fun rememberDemoState(): DemoState {
  val nav = rememberNavController()
  val cameraState = rememberCameraState()
  val styleState = rememberStyleState()
  return remember(nav, cameraState, styleState) { DemoState(nav, cameraState, styleState) }
}
