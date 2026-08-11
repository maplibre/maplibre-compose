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
import org.maplibre.compose.demoapp.demos.MapControlsDemo
import org.maplibre.compose.demoapp.demos.MapManipulationDemo
import org.maplibre.compose.demoapp.demos.MarkersDemo
import org.maplibre.compose.demoapp.demos.StyleSelectorDemo
import org.maplibre.compose.demoapp.demos.UserLocationDemo
import org.maplibre.compose.demoapp.util.Platform
import org.maplibre.compose.location.UserLocationState
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberDefaultOrientationProvider
import org.maplibre.compose.location.rememberNullLocationProvider
import org.maplibre.compose.location.rememberNullOrientationProvider
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

/** Which set of controls [DemoMap] draws on top of the map. */
enum class MapControls {
  Foundation,
  Material3,
  None,
}

class MapControlsState {
  var controls by mutableStateOf(MapControls.Material3)
}

class DemoState(
  val nav: NavHostController,
  val cameraState: CameraState,
  val styleState: StyleState,
  val locationState: UserLocationState,
  val locationPermissionState: LocationPermissionState,
  val mapManipulationState: MapManipulationState = MapManipulationState(),
  val mapControlsState: MapControlsState = MapControlsState(),
) {

  val mapClickEvents = mutableStateListOf<MapClickEvent>()

  val frameRateState = FrameRateState()

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
      MapControlsDemo,
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
  val locationProvider =
    key(locationPermissionState.hasPermission) {
      if (locationPermissionState.hasPermission) {
        // Android Lint reads the permission check on the line above as a plain boolean.
        //noinspection MissingPermission
        rememberDefaultLocationProvider()
      } else {
        rememberNullLocationProvider()
      }
    }
  val orientationProvider =
    key(locationPermissionState.hasPermission) {
      if (locationPermissionState.hasPermission) {
        rememberDefaultOrientationProvider()
      } else {
        rememberNullOrientationProvider()
      }
    }
  val locationState = rememberUserLocationState(locationProvider, orientationProvider)

  return remember(nav, cameraState, styleState, locationState, locationPermissionState) {
    DemoState(nav, cameraState, styleState, locationState, locationPermissionState)
  }
}

interface LocationPermissionState {
  val hasPermission: Boolean

  fun requestPermission()
}

@Composable expect fun rememberLocationPermissionState(): LocationPermissionState
