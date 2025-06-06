package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.compass.Location
import dev.jordond.compass.Priority
import dev.jordond.compass.geolocation.LocationRequest
import dev.jordond.compass.geolocation.TrackingStatus
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.compose.source.rememberGeoJsonSource
import dev.sargunv.maplibrecompose.core.CameraMoveReason
import dev.sargunv.maplibrecompose.core.source.GeoJsonData
import dev.sargunv.maplibrecompose.demoapp.DEFAULT_STYLE
import dev.sargunv.maplibrecompose.demoapp.Demo
import dev.sargunv.maplibrecompose.demoapp.DemoMapControls
import dev.sargunv.maplibrecompose.demoapp.DemoOrnamentSettings
import dev.sargunv.maplibrecompose.demoapp.DemoScaffold
import dev.sargunv.maplibrecompose.demoapp.generated.Res
import dev.sargunv.maplibrecompose.demoapp.generated.location_searching
import dev.sargunv.maplibrecompose.demoapp.generated.my_location_filled
import dev.sargunv.maplibrecompose.demoapp.generated.navigation_filled
import dev.sargunv.maplibrecompose.demoapp.getGeolocator
import dev.sargunv.maplibrecompose.demoapp.util.LocationPuckLayers
import dev.sargunv.maplibrecompose.material3.controls.PointerPinButton
import io.github.dellisd.spatialk.geojson.Point
import io.github.dellisd.spatialk.geojson.Position
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.vectorResource

object UserLocationDemo : Demo {
  override val name = "User location"
  override val description =
    "Display and track the user's location using the Compass Geolocation library."

  @Composable
  override fun Component(navigateUp: () -> Unit) {
    val cameraState = rememberCameraState()
    val styleState = rememberStyleState()

    val locationStatus by rememberLocationStatusState()
    val location by derivedStateOf { locationStatus.toLocationOrNull() }

    var mode by remember { mutableStateOf(LocationTrackingMode.Free) }

    MapGestureEffect(cameraState) { mode = LocationTrackingMode.Free }

    // disengage automatically if lacking data
    LaunchedEffect(location, mode) {
      val location = location
      when {
        location == null -> mode = LocationTrackingMode.Free
        location.azimuth == null && mode == LocationTrackingMode.FollowPositionAndBearing ->
          mode = LocationTrackingMode.FollowPosition
      }
    }

    // follow location
    LaunchedEffect(mode, location, cameraState) {
      val location = location
      if (location == null || mode == LocationTrackingMode.Free) return@LaunchedEffect
      val azimuth = location.azimuth

      val target =
        cameraState.position.copy(
          target = location.position,
          zoom = max(cameraState.position.zoom, 12.0),
          bearing =
            // TODO azimuth doesn't seem to work very well for bearing
            // perhaps we should look into other device sensors for bearing?
            if (mode == LocationTrackingMode.FollowPositionAndBearing && azimuth != null)
              azimuth.degrees.toDouble()
            else cameraState.position.bearing,
        )

      cameraState.animateTo(target)
    }

    DemoScaffold(this, navigateUp) {
      Column {
        Box(modifier = Modifier.weight(1f)) {
          MaplibreMap(
            styleUri = DEFAULT_STYLE,
            cameraState = cameraState,
            styleState = styleState,
            ornamentSettings = DemoOrnamentSettings(),
          ) {
            location?.let { LocationPuck(it) }
          }

          location?.let {
            PointerPinButton(
              cameraState,
              it.position,
              onClick = {
                if (mode == LocationTrackingMode.Free) mode = LocationTrackingMode.FollowPosition
              },
            ) {
              Text("📍", fontSize = 28.sp)
            }
          }

          DemoMapControls(
            cameraState,
            styleState,
            onCompassClick = {
              if (mode == LocationTrackingMode.FollowPositionAndBearing)
                mode = LocationTrackingMode.FollowPosition
            },
          ) {
            ElevatedButton(
              onClick = { mode = mode.nextMode },
              shape = CircleShape,
              modifier = Modifier.requiredSize(48.dp).aspectRatio(1f),
              contentPadding = PaddingValues(8.dp),
            ) {
              AnimatedContent(mode) { mode ->
                when (mode) {
                  LocationTrackingMode.Free ->
                    Icon(
                      vectorResource(Res.drawable.location_searching),
                      contentDescription = "Free camera",
                    )
                  LocationTrackingMode.FollowPosition ->
                    Icon(
                      vectorResource(Res.drawable.my_location_filled),
                      contentDescription = "Camera following position",
                    )
                  LocationTrackingMode.FollowPositionAndBearing ->
                    Icon(
                      vectorResource(Res.drawable.navigation_filled),
                      contentDescription = "Camera following position and bearing",
                    )
                }
              }
            }
          }
        }
      }
    }
  }
}

enum class LocationTrackingMode {
  Free,
  FollowPosition,
  FollowPositionAndBearing;

  val nextMode
    get() = entries[(ordinal + 1) % entries.size]
}

@Composable
private fun MapGestureEffect(cameraState: CameraState, block: CoroutineScope.() -> Unit) {
  LaunchedEffect(cameraState.isCameraMoving, cameraState.moveReason) {
    if (cameraState.isCameraMoving && cameraState.moveReason == CameraMoveReason.GESTURE) block()
  }
}

@Composable
private fun rememberLocationStatusState(): State<TrackingStatus> {
  return remember { getGeolocator() }
    .track(
      LocationRequest(priority = Priority.HighAccuracy, interval = 0.2.seconds.inWholeMilliseconds)
    )
    .collectAsState(TrackingStatus.Idle)
}

@Composable
private fun LocationPuck(location: Location) {
  val locationSource =
    rememberGeoJsonSource(id = "location", data = GeoJsonData.Features(Point(location.position)))
  LocationPuckLayers(idPrefix = "user-location", locationSource = locationSource)
}

private fun TrackingStatus.toLocationOrNull(): Location? {
  return when (this) {
    is TrackingStatus.Update -> location
    else -> null
  }
}

private val Location.position
  get() = Position(latitude = coordinates.latitude, longitude = coordinates.longitude)
