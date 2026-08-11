package org.maplibre.compose.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
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
import org.maplibre.compose.generated.Res
import org.maplibre.compose.generated.compass
import org.maplibre.compose.generated.compass_needle
import org.maplibre.compose.util.AngleMath

/**
 * A compass that points north and returns the camera to [getHomePosition] when it is clicked.
 *
 * This component draws with Compose Foundation alone. The
 * [Material 3 module][org.maplibre.compose.material3] provides a themed version of it.
 *
 * @param cameraState The camera that the needle follows and that a click resets.
 * @param onClick Called after the camera animation starts.
 * @param style Colors, shape, and elevation of the button behind the needle.
 * @param contentDescription Accessibility label for the needle.
 * @param size Width and height of the button.
 * @param contentPadding Gap between the button edge and the needle.
 * @param needlePainter The needle artwork, drawn without a tint.
 * @param getHomePosition The camera position that a click returns to.
 */
@Composable
public fun CompassButton(
  cameraState: CameraState,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
  style: CompassButtonStyle = CompassDefaults.style(),
  contentDescription: String = CompassDefaults.contentDescription(),
  size: Dp = 48.dp,
  contentPadding: PaddingValues = PaddingValues(size / 6),
  needlePainter: Painter = CompassDefaults.needlePainter(),
  getHomePosition: (CameraPosition) -> CameraPosition = { it.copy(bearing = 0.0, tilt = 0.0) },
) {
  val coroutineScope = rememberCoroutineScope()
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val shadowElevation by
    animateDpAsState(if (hovered) style.hoveredShadowElevation else style.shadowElevation)

  Box(
    modifier
      .requiredSize(size)
      .aspectRatio(1f)
      .shadow(shadowElevation, style.shape, clip = false)
      .background(style.containerColor, style.shape)
      .clip(style.shape)
      .clickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        role = Role.Button,
      ) {
        coroutineScope.launch { cameraState.animateTo(getHomePosition(cameraState.position)) }
        onClick()
      }
      .padding(contentPadding),
    contentAlignment = Alignment.Center,
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

/**
 * A [CompassButton] that appears when the camera turns away from [getHomePosition] and fades out
 * once the camera returns to it.
 *
 * This component draws with Compose Foundation alone. The
 * [Material 3 module][org.maplibre.compose.material3] provides a themed version of it.
 *
 * @param visibilityDuration How long the button stays visible after the camera returns home.
 * @param slop How far the camera may turn from [getHomePosition] before the button appears, in
 *   degrees.
 */
@Composable
public fun DisappearingCompassButton(
  cameraState: CameraState,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
  style: CompassButtonStyle = CompassDefaults.style(),
  contentDescription: String = CompassDefaults.contentDescription(),
  size: Dp = 48.dp,
  contentPadding: PaddingValues = PaddingValues(size / 6),
  needlePainter: Painter = CompassDefaults.needlePainter(),
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
      style = style,
      contentDescription = contentDescription,
      size = size,
      contentPadding = contentPadding,
      needlePainter = needlePainter,
      getHomePosition = getHomePosition,
    )
  }
}

public object CompassDefaults {
  /** Reads over both light and dark basemaps, in the absence of a theme to draw colors from. */
  public val ContainerColor: Color = Color.White.copy(alpha = 0.9f)

  public val ShadowElevation: Dp = 1.dp

  /** Matches the lift that Material's elevated button gives a hovered pointer. */
  public val HoveredShadowElevation: Dp = 3.dp

  /** Accessibility label for the needle. */
  @Composable public fun contentDescription(): String = stringResource(Res.string.compass)

  /** The two-tone needle that points north. */
  @Composable public fun needlePainter(): Painter = painterResource(Res.drawable.compass_needle)

  public fun style(): CompassButtonStyle = CompassButtonStyle()
}

@Immutable
public data class CompassButtonStyle(
  /** Color of the button behind the needle. */
  public val containerColor: Color = CompassDefaults.ContainerColor,

  /** Shadow elevation of the button at rest. */
  public val shadowElevation: Dp = CompassDefaults.ShadowElevation,

  /** Shadow elevation of the button while a pointer hovers over it. */
  public val hoveredShadowElevation: Dp = CompassDefaults.HoveredShadowElevation,

  /** Shape of the button. */
  public val shape: Shape = CircleShape,
)
