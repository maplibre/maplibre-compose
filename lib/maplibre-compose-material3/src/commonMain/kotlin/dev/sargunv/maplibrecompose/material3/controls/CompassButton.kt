package dev.sargunv.maplibrecompose.material3.controls

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
import androidx.compose.foundation.layout.size
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
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.material3.generated.Res
import dev.sargunv.maplibrecompose.material3.generated.compass
import dev.sargunv.maplibrecompose.material3.generated.compass_needle
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

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
) {
  val coroutineScope = rememberCoroutineScope()
  ElevatedButton(
    modifier = modifier.requiredSize(size).aspectRatio(1f),
    onClick = {
      coroutineScope.launch {
        cameraState.animateTo(cameraState.position.copy(bearing = 0.0, tilt = 0.0))
      }
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
) {
  val visible = remember { MutableTransitionState(false) }

  val shouldBeVisible by derivedStateOf {
    val tilt = abs(cameraState.position.tilt) % 360
    val bearing = abs(cameraState.position.bearing) % 360
    tilt in 0.5..359.5 || bearing in 0.5..359.5
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
    )
  }
}
