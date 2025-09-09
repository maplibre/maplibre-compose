package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.demoapp.demos.AnimatedLayerDemo
import org.maplibre.compose.demoapp.demos.CameraStateDemo
import org.maplibre.compose.demoapp.demos.ClusteredPointsDemo
import org.maplibre.compose.demoapp.demos.Demo
import org.maplibre.compose.demoapp.demos.MapClickDemo
import org.maplibre.compose.demoapp.demos.MarkersDemo
import org.maplibre.compose.demoapp.demos.StyleSelectorDemo
import org.maplibre.compose.demoapp.util.Platform
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.rememberStyleState

class DemoState(
  val nav: NavHostController,
  val cameraState: CameraState,
  val styleState: StyleState,
) {

  val mapClickEvents = mutableStateListOf<MapClickEvent>()

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
      MapClickDemo,
      ClusteredPointsDemo,
    ) + Platform.extraDemos)

  var selectedStyle by mutableStateOf<DemoStyle>(Protomaps.Light)
  var renderOptions by mutableStateOf(RenderOptions.Standard)
  var gestureOptions by mutableStateOf(GestureOptions.Standard)

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
