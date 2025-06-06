package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.MaplibreMap
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.compose.source.rememberGeoJsonSource
import dev.sargunv.maplibrecompose.core.CameraMoveReason
import dev.sargunv.maplibrecompose.core.CameraPosition
import dev.sargunv.maplibrecompose.core.source.GeoJsonData
import dev.sargunv.maplibrecompose.demoapp.DEFAULT_STYLE
import dev.sargunv.maplibrecompose.demoapp.Demo
import dev.sargunv.maplibrecompose.demoapp.DemoMapControls
import dev.sargunv.maplibrecompose.demoapp.DemoOrnamentSettings
import dev.sargunv.maplibrecompose.demoapp.DemoScaffold
import dev.sargunv.maplibrecompose.demoapp.Platform
import dev.sargunv.maplibrecompose.demoapp.PositionVectorConverter
import dev.sargunv.maplibrecompose.demoapp.supportsLayers
import dev.sargunv.maplibrecompose.demoapp.util.LocationPuckLayers
import dev.sargunv.maplibrecompose.material3.controls.PointerPinButton
import io.github.dellisd.spatialk.geojson.Point
import io.github.dellisd.spatialk.geojson.Position
import kotlin.math.roundToInt

private val START_POINT = Position(longitude = -122.4194, latitude = 37.7749)
private val END_POINT = Position(longitude = -122.3954, latitude = 37.7939)
private const val MIN_ZOOM = 9
private const val MAX_ZOOM = 15

object CameraFollowDemo : Demo {
  override val name = "Camera follow"
  override val description = "Make the camera follow a point on the map."

  @Composable
  override fun Component(navigateUp: () -> Unit) {
    DemoScaffold(this, navigateUp) {
      Column {
        val animatedPosition by animateTestPosition(START_POINT, END_POINT)
        var followAtZoom by remember { mutableStateOf((MIN_ZOOM + MAX_ZOOM) / 2) }
        var isFollowing by remember { mutableStateOf(true) }

        val cameraState =
          rememberAnimatedFollowCamera(
            targetPos = animatedPosition,
            targetZoom = followAtZoom.toFloat(),
            isFollowing = isFollowing,
          )
        val styleState = rememberStyleState()

        LaunchedEffect(cameraState.moveReason) {
          if (cameraState.moveReason == CameraMoveReason.GESTURE) {
            isFollowing = false
            cameraState.position = cameraState.position.copy()
          }
        }

        Box(modifier = Modifier.weight(1f)) {
          MaplibreMap(
            styleUri = DEFAULT_STYLE,
            cameraState = cameraState,
            styleState = styleState,
            ornamentSettings = DemoOrnamentSettings(),
          ) {
            if (Platform.supportsLayers) {
              LocationPuckLayers(
                idPrefix = "target",
                locationSource =
                  rememberGeoJsonSource("target", GeoJsonData.Features(Point(animatedPosition))),
              )
            }
          }

          PointerPinButton(
            cameraState = cameraState,
            targetPosition = animatedPosition,
            onClick = { isFollowing = true },
          ) {
            Text("🚊", fontSize = 28.sp)
          }

          DemoMapControls(
            cameraState,
            styleState,
            onCompassClick = {
              isFollowing = false
              cameraState.position = cameraState.position.copy()
            },
          )
        }

        Text(
          text = "Move reason: ${cameraState.moveReason.name}",
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
        )

        FollowControls(
          currentZoom = followAtZoom,
          isFollowing = isFollowing,
          onZoomChange = { followAtZoom = it },
          onStartFollowing = {
            isFollowing = true
            followAtZoom = cameraState.position.zoom.roundToInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
          },
        )
      }
    }
  }
}

@Composable
private fun animateTestPosition(start: Position, end: Position): State<Position> {
  val infiniteTransition = rememberInfiniteTransition()
  return infiniteTransition.animateValue(
    start,
    end,
    typeConverter = remember { PositionVectorConverter(origin = START_POINT) },
    animationSpec =
      remember {
        infiniteRepeatable(
          animation = tween(durationMillis = 30_000),
          repeatMode = RepeatMode.Reverse,
        )
      },
  )
}

@Composable
private fun rememberAnimatedFollowCamera(
  targetPos: Position,
  targetZoom: Float,
  isFollowing: Boolean,
): CameraState {
  val cameraState =
    rememberCameraState(
      firstPosition = CameraPosition(target = targetPos, zoom = targetZoom.toDouble())
    )

  LaunchedEffect(isFollowing, targetPos, targetZoom) {
    if (isFollowing)
      cameraState.animateTo(
        cameraState.position.copy(target = targetPos, zoom = targetZoom.toDouble())
      )
  }

  return cameraState
}

@Composable
private fun FollowControls(
  currentZoom: Int,
  isFollowing: Boolean,
  onZoomChange: (Int) -> Unit,
  onStartFollowing: () -> Unit,
) {
  Row(
    modifier = Modifier.padding(16.dp).fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceEvenly,
  ) {
    Button(
      enabled = isFollowing && currentZoom > MIN_ZOOM,
      onClick = { onZoomChange((currentZoom - 1).coerceAtLeast(MIN_ZOOM)) },
    ) {
      Text("Zoom out")
    }
    Button(enabled = !isFollowing, onClick = onStartFollowing) { Text("Follow") }
    Button(
      enabled = isFollowing && currentZoom < MAX_ZOOM,
      onClick = { onZoomChange((currentZoom + 1).coerceAtMost(MAX_ZOOM)) },
    ) {
      Text("Zoom in")
    }
  }
}
