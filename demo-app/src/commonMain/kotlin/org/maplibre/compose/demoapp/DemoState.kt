package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import org.maplibre.compose.demoapp.demos.MapManipulationDemo
import org.maplibre.compose.demoapp.demos.MarkersDemo
import org.maplibre.compose.demoapp.demos.StyleSelectorDemo
import org.maplibre.compose.demoapp.demos.UserLocationDemo
import org.maplibre.compose.demoapp.util.Platform
import org.maplibre.compose.location.UserLocationState
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberNullLocationProvider
import org.maplibre.compose.location.rememberUserLocationState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.rememberStyleState

enum class MapSize {
  Full,
  Half,
  Fixed,
}

enum class MapPosition {
  TopLeft,
  TopCenter,
  TopRight,
  CenterLeft,
  Center,
  CenterRight,
  BottomLeft,
  BottomCenter,
  BottomRight,
}

class MapManipulationState {
  var isVisible by mutableStateOf(true)
  var size by mutableStateOf(MapSize.Full)
  var position by mutableStateOf(MapPosition.Center)
}

class DemoState(
  val nav: NavHostController,
  val cameraState: CameraState,
  val styleState: StyleState,
  val locationState: UserLocationState,
  val locationPermissionState: LocationPermissionState,
  val mapManipulationState: MapManipulationState = MapManipulationState(),
) {

  val mapClickEvents = mutableStateListOf<MapClickEvent>()

  // TODO:
  // Camera follow
  // Image source

  val demos =
    (listOf(
      StyleSelectorDemo,
      CameraStateDemo,
      AnimatedLayerDemo,
      MarkersDemo,
      MapClickDemo,
      ClusteredPointsDemo,
      UserLocationDemo,
      MapManipulationDemo,
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

  val locationPermissionState = rememberLocationPermissionState()
  // this keying and swapping of LocationProviders is necessary because of the way the demo is set
  // up
  //
  // In a normal app, it would be best to avoid creating a LocationProvider and everything dependent
  // on it altogether, if no permission has been granted. The at look at GmsLocationDemo on Android
  // for an example of this.
  val locationProvider =
    key(locationPermissionState.hasPermission) {
      if (locationPermissionState.hasPermission) {
        rememberDefaultLocationProvider()
      } else {
        rememberNullLocationProvider()
      }
    }
  val locationState = rememberUserLocationState(locationProvider)

  return remember(nav, cameraState, styleState, locationPermissionState) {
    DemoState(nav, cameraState, styleState, locationState, locationPermissionState)
  }
}

interface LocationPermissionState {
  val hasPermission: Boolean

  fun requestPermission()
}

@Composable expect fun rememberLocationPermissionState(): LocationPermissionState
