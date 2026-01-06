package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.lock_24px
import org.maplibre.compose.demoapp.generated.lock_open_24px
import org.maplibre.compose.location.BearingUpdate
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationTrackingEffect
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.material3.LocationPuckDefaults
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees

object UserLocationDemo : Demo {
  override val name = "User Location"

  private var locationClickedCount by mutableIntStateOf(0)
  private var trackLocation by mutableStateOf(false)
  private var bearingUpdate by mutableStateOf(BearingUpdate.TRACK_AUTOMATIC)

  private var lockCamera by mutableStateOf(true)

  @Composable
  override fun MapContent(state: DemoState, isOpen: Boolean) {
    if (!isOpen) return

    LocationTrackingEffect(
      locationState = state.locationState,
      enabled = trackLocation,
      trackBearing = bearingUpdate == BearingUpdate.TRACK_AUTOMATIC,
    ) {
      state.cameraState.updateFromLocation(updateBearing = bearingUpdate)
    }

    // which of these additional effects is desired, depends on the use case
    // without either effect, the camera can be adjusted between location updates, but will reset
    // whenever a new location is received
    if (lockCamera) {
      // this effect ensures the corresponding camera gestures are disabled while location is
      // tracked
      LaunchedEffect(trackLocation, bearingUpdate) {
        state.gestureOptions =
          if (trackLocation) {
            when (bearingUpdate) {
              BearingUpdate.IGNORE -> GestureOptions.PositionLocked
              BearingUpdate.ALWAYS_NORTH -> GestureOptions.ZoomOnly
              BearingUpdate.TRACK_AUTOMATIC -> GestureOptions.ZoomOnly
              else -> TODO()
            }
          } else {
            GestureOptions.Standard
          }
      }
    } else {
      // this effect cancels location tracking, when the user moves the camera using a gesture
      LaunchedEffect(state.cameraState.moveReason) {
        if (state.cameraState.moveReason == CameraMoveReason.GESTURE) {
          trackLocation = false
        }
      }
    }

    LocationPuck(
      idPrefix = "user-location",
      locationState = state.locationState,
      bearing =
        if (bearingUpdate != BearingUpdate.IGNORE) state.locationState.location?.course else null,
      cameraState = state.cameraState,
      accuracyThreshold = 0f,
      colors = LocationPuckDefaults.colors(),
      onClick = { location ->
        locationClickedCount++
        if (trackLocation) {
          bearingUpdate =
            when (bearingUpdate) {
              BearingUpdate.TRACK_AUTOMATIC -> BearingUpdate.ALWAYS_NORTH
              BearingUpdate.ALWAYS_NORTH -> BearingUpdate.IGNORE
              BearingUpdate.IGNORE -> {
                trackLocation = false
                BearingUpdate.IGNORE
              }
              else -> TODO()
            }
        } else {
          bearingUpdate = BearingUpdate.TRACK_AUTOMATIC
          trackLocation = true

          if (state.cameraState.position.zoom < 16) {
            state.cameraState.position =
              CameraPosition(
                target =
                  state.locationState.location?.position?.value
                    ?: state.cameraState.position.target,
                zoom = 16.0,
              )
          }
        }
      },
    )
  }

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    if (!state.locationPermissionState.hasPermission) {
      Button(onClick = state.locationPermissionState::requestPermission) {
        Text("Request permission")
      }
    } else {
      CardColumn {
        Text("User Location clicked $locationClickedCount times")
        Text(
          buildString {
            if (trackLocation) {
              append("camera is tracking location and ")
              append(
                when (bearingUpdate) {
                  BearingUpdate.IGNORE -> "ignoring bearing"
                  BearingUpdate.ALWAYS_NORTH -> "locked to north bearing"
                  BearingUpdate.TRACK_AUTOMATIC -> "bearing"
                  BearingUpdate.TRACK_COURSE -> "course"
                // BearingUpdate.TRACK_ORIENTATION -> "orientation"
                }
              )
            } else {
              append("location and bearing not tracked by camera")
            }
          }
        )
        Button(onClick = { lockCamera = !lockCamera }) {
          Icon(
            painter =
              if (lockCamera) {
                painterResource(Res.drawable.lock_24px)
              } else {
                painterResource(Res.drawable.lock_open_24px)
              },
            contentDescription = null,
          )
          Text(
            if (lockCamera) {
              "Lock camera when tracking location"
            } else {
              "Cancel tracking when camera is moved"
            }
          )
        }
      }

      state.locationState.let { state ->
        Card {
          Column(
            modifier = Modifier.padding(8.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              "Course: ${state.location?.course?.value?.smallestRotationTo(Bearing.North)?.inDegrees?.roundToInt()} +- ${state.location?.course?.accuracy?.inDegrees?.roundToInt()}"
            )
            Text(
              "Orientation: ${
                state.orientation?.orientation?.value?.smallestRotationTo(
                  Bearing.North
                )?.inDegrees?.roundToInt()
              } +- ${state.orientation?.orientation?.accuracy?.inDegrees?.roundToInt()}"
            )
          }
        }
      }
    }
  }
}
