package org.maplibre.compose.material3

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.material3.generated.Res
import org.maplibre.compose.material3.generated.compass
import org.maplibre.compose.material3.generated.compass_needle
import org.maplibre.compose.material3.util.AngleMath

@Composable
public fun CompassButton(
  cameraState: CameraState,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
  colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
  contentDescription: String = stringResource(Res.string.compass),
  size: Dp = 48.dp,
  contentPadding: PaddingValues = PaddingValues(size / 6),
  shape: Shape = CircleShape,
  needlePainter: Painter = painterResource(Res.drawable.compass_needle),
  getHomePosition: (CameraPosition) -> CameraPosition = { it.copy(bearing = 0.0, tilt = 0.0) },
) {
  val coroutineScope = rememberCoroutineScope()
  ElevatedButton(
    modifier = modifier.requiredSize(size).aspectRatio(1f),
    onClick = {
      coroutineScope.launch { cameraState.animateTo(getHomePosition(cameraState.position)) }
      onClick()
    },
    shape = shape,
    colors = colors,
    contentPadding = contentPadding,
  ) {
    Image(
      painter = needlePainter,
      contentDescription = contentDescription,
      modifier =
        Modifier.fillMaxSize()
          .graphicsLayer(
            rotationZ = -cameraState.position.bearing.toFloat(),
            rotationX = cameraState.position.tilt.toFloat(),
          ),
    )
  }
}

@Composable
public fun DisappearingCompassButton(
  cameraState: CameraState,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
  colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
  contentDescription: String = stringResource(Res.string.compass),
  size: Dp = 48.dp,
  contentPadding: PaddingValues = PaddingValues(size / 6),
  shape: Shape = CircleShape,
  needlePainter: Painter = painterResource(Res.drawable.compass_needle),
  visibilityDuration: Duration = 1.seconds,
  enterTransition: EnterTransition = fadeIn(),
  exitTransition: ExitTransition = fadeOut(),
  getHomePosition: (CameraPosition) -> CameraPosition = { it.copy(bearing = 0.0, tilt = 0.0) },
  slop: Double = 0.5,
) {
  val visible = remember { MutableTransitionState(false) }

  val homePosition by remember { derivedStateOf { getHomePosition(cameraState.position) } }

  val shouldBeVisible by remember {
    derivedStateOf {
      with(AngleMath) {
        val tiltDiff = cameraState.position.tilt.diff(homePosition.tilt).absoluteValue
        val bearingDiff = cameraState.position.bearing.diff(homePosition.bearing).absoluteValue
        tiltDiff > slop || bearingDiff > slop
      }
    }
  }

  LaunchedEffect(shouldBeVisible) {
    if (shouldBeVisible) {
      visible.targetState = true
    } else {
      delay(visibilityDuration)
      visible.targetState = false
    }
  }

  AnimatedVisibility(
    visibleState = visible,
    modifier = modifier,
    enter = enterTransition,
    exit = exitTransition,
  ) {
    CompassButton(
      cameraState = cameraState,
      modifier = modifier,
      onClick = onClick,
      colors = colors,
      contentDescription = contentDescription,
      size = size,
      contentPadding = contentPadding,
      shape = shape,
      needlePainter = needlePainter,
      getHomePosition = getHomePosition,
    )
  }
}
