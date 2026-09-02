package org.maplibre.compose.material3

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.overlay.ZoomButtons as BaseZoomButtons
import org.maplibre.compose.overlay.ZoomButtonsDefaults
import org.maplibre.compose.overlay.ZoomButtonsStyle

/**
 * Buttons that zoom the camera in and out, drawn as one vertical segmented container.
 *
 * This is [org.maplibre.compose.overlay.ZoomButtons] with the colors, shape, and elevation of an
 * [ElevatedButton].
 *
 * @param onZoomIn Called after the zoom-in animation starts.
 * @param onZoomOut Called after the zoom-out animation starts.
 * @param colors Container and content colors, defaulting to those of an [ElevatedButton].
 * @param dividerColor Color of the divider between the buttons.
 * @param contentDescriptionZoomIn Accessibility label for the zoom-in button.
 * @param contentDescriptionZoomOut Accessibility label for the zoom-out button.
 * @param width Width of the container. Each button is a square of this size.
 * @param contentPadding Gap between a button edge and its icon.
 * @param shape Shape of the container.
 * @param zoomInPainter The plus artwork, tinted with the content color.
 * @param zoomOutPainter The minus artwork, tinted with the content color.
 * @param getZoomInPosition The camera position that the zoom-in button animates to.
 * @param getZoomOutPosition The camera position that the zoom-out button animates to.
 */
@Composable
public fun MapOverlayScope.ZoomButtons(
  modifier: Modifier = Modifier,
  onZoomIn: () -> Unit = {},
  onZoomOut: () -> Unit = {},
  colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
  dividerColor: Color = MaterialTheme.colorScheme.outlineVariant,
  contentDescriptionZoomIn: String = ZoomButtonsDefaults.contentDescriptionZoomIn(),
  contentDescriptionZoomOut: String = ZoomButtonsDefaults.contentDescriptionZoomOut(),
  width: Dp = 48.dp,
  contentPadding: PaddingValues = PaddingValues(12.dp),
  shape: Shape = RoundedCornerShape(percent = 50),
  zoomInPainter: Painter = ZoomButtonsDefaults.zoomInPainter(),
  zoomOutPainter: Painter = ZoomButtonsDefaults.zoomOutPainter(),
  getZoomInPosition: (CameraPosition) -> CameraPosition = { it.copy(zoom = it.zoom + 1) },
  getZoomOutPosition: (CameraPosition) -> CameraPosition = { it.copy(zoom = it.zoom - 1) },
) {
  BaseZoomButtons(
    modifier = modifier,
    onZoomIn = onZoomIn,
    onZoomOut = onZoomOut,
    style = elevatedButtonStyle(colors, dividerColor, shape),
    contentDescriptionZoomIn = contentDescriptionZoomIn,
    contentDescriptionZoomOut = contentDescriptionZoomOut,
    width = width,
    contentPadding = contentPadding,
    zoomInPainter = zoomInPainter,
    zoomOutPainter = zoomOutPainter,
    getZoomInPosition = getZoomInPosition,
    getZoomOutPosition = getZoomOutPosition,
  )
}

/**
 * The elevations are the ones [ButtonDefaults.elevatedButtonElevation] resolves to. They are not
 * reachable through [ButtonColors], and [ElevatedButton] changes elevation on hover alone.
 */
private fun elevatedButtonStyle(colors: ButtonColors, dividerColor: Color, shape: Shape) =
  ZoomButtonsStyle(
    containerColor = colors.containerColor,
    contentColor = colors.contentColor,
    dividerColor = dividerColor,
    shadowElevation = 1.dp,
    hoveredShadowElevation = 3.dp,
    shape = shape,
  )
