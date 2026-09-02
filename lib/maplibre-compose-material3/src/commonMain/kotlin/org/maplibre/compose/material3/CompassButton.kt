package org.maplibre.compose.material3

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.overlay.CompassButton as BaseCompassButton
import org.maplibre.compose.overlay.CompassButtonStyle
import org.maplibre.compose.overlay.CompassDefaults
import org.maplibre.compose.overlay.DisappearingCompassButton as BaseDisappearingCompassButton
import org.maplibre.compose.overlay.MapOverlayScope

/**
 * A compass that points north and returns the camera to [getHomePosition] when it is clicked.
 *
 * This is [org.maplibre.compose.overlay.CompassButton] with the colors, shape, and elevation of an
 * [ElevatedButton].
 *
 * @param onClick Called after the camera animation starts.
 * @param colors Container and content colors, defaulting to those of an [ElevatedButton].
 * @param contentDescription Accessibility label for the needle.
 * @param size Width and height of the button.
 * @param contentPadding Gap between the button edge and the needle.
 * @param shape Shape of the button.
 * @param needlePainter The needle artwork, drawn without a tint.
 * @param getHomePosition The camera position that a click returns to.
 */
@Composable
public fun MapOverlayScope.CompassButton(
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
  colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
  contentDescription: String = CompassDefaults.contentDescription(),
  size: Dp = 48.dp,
  contentPadding: PaddingValues = PaddingValues(size / 6),
  shape: Shape = CircleShape,
  needlePainter: Painter = CompassDefaults.needlePainter(),
  getHomePosition: (CameraPosition) -> CameraPosition = { it.copy(bearing = 0.0, tilt = 0.0) },
) {
  BaseCompassButton(
    modifier = modifier,
    onClick = onClick,
    style = elevatedButtonStyle(colors, shape),
    contentDescription = contentDescription,
    size = size,
    contentPadding = contentPadding,
    needlePainter = needlePainter,
    getHomePosition = getHomePosition,
  )
}

/**
 * A [CompassButton] that appears when the camera turns away from [getHomePosition] and fades out
 * once the camera returns to it.
 *
 * This is [org.maplibre.compose.overlay.DisappearingCompassButton] with the colors, shape, and
 * elevation of an [ElevatedButton].
 *
 * @param visibilityDuration How long the button stays visible after the camera returns home.
 * @param slop How far the camera may turn from [getHomePosition] before the button appears, in
 *   degrees.
 */
@Composable
public fun MapOverlayScope.DisappearingCompassButton(
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
  colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
  contentDescription: String = CompassDefaults.contentDescription(),
  size: Dp = 48.dp,
  contentPadding: PaddingValues = PaddingValues(size / 6),
  shape: Shape = CircleShape,
  needlePainter: Painter = CompassDefaults.needlePainter(),
  visibilityDuration: Duration = 1.seconds,
  enterTransition: EnterTransition = fadeIn(),
  exitTransition: ExitTransition = fadeOut(),
  getHomePosition: (CameraPosition) -> CameraPosition = { it.copy(bearing = 0.0, tilt = 0.0) },
  slop: Double = 0.5,
) {
  BaseDisappearingCompassButton(
    modifier = modifier,
    onClick = onClick,
    style = elevatedButtonStyle(colors, shape),
    contentDescription = contentDescription,
    size = size,
    contentPadding = contentPadding,
    needlePainter = needlePainter,
    visibilityDuration = visibilityDuration,
    enterTransition = enterTransition,
    exitTransition = exitTransition,
    getHomePosition = getHomePosition,
    slop = slop,
  )
}

/**
 * The elevations are the ones [ButtonDefaults.elevatedButtonElevation] resolves to. They are not
 * reachable through [ButtonColors], and [ElevatedButton] changes elevation on hover alone.
 */
private fun elevatedButtonStyle(colors: ButtonColors, shape: Shape) =
  CompassButtonStyle(
    containerColor = colors.containerColor,
    shadowElevation = 1.dp,
    hoveredShadowElevation = 3.dp,
    shape = shape,
  )
