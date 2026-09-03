package org.maplibre.compose.overlay

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.generated.Res
import org.maplibre.compose.generated.add
import org.maplibre.compose.generated.remove
import org.maplibre.compose.generated.zoom_in
import org.maplibre.compose.generated.zoom_out

/**
 * Buttons that zoom the camera in and out, drawn as one vertical segmented container.
 *
 * This component draws with Compose Foundation alone. The Material 3 module provides a themed
 * version of it.
 *
 * @param onZoomIn Called when the zoom-in button is clicked, once its animation is requested.
 * @param onZoomOut Called when the zoom-out button is clicked, once its animation is requested.
 * @param style Colors, shape, and elevation of the container behind the buttons.
 * @param contentDescriptionZoomIn Accessibility label for the zoom-in button.
 * @param contentDescriptionZoomOut Accessibility label for the zoom-out button.
 * @param width Width of the container. Each button is a square of this size.
 * @param contentPadding Gap between a button edge and its icon.
 * @param zoomInPainter The plus artwork, tinted with [ZoomButtonsStyle.contentColor].
 * @param zoomOutPainter The minus artwork, tinted with [ZoomButtonsStyle.contentColor].
 * @param getZoomInPosition The camera position that the zoom-in button animates to.
 * @param getZoomOutPosition The camera position that the zoom-out button animates to.
 */
@Composable
public fun MapOverlayScope.ZoomButtons(
  modifier: Modifier = Modifier,
  onZoomIn: () -> Unit = {},
  onZoomOut: () -> Unit = {},
  style: ZoomButtonsStyle = ZoomButtonsDefaults.style(),
  contentDescriptionZoomIn: String = ZoomButtonsDefaults.contentDescriptionZoomIn(),
  contentDescriptionZoomOut: String = ZoomButtonsDefaults.contentDescriptionZoomOut(),
  width: Dp = 48.dp,
  contentPadding: PaddingValues = PaddingValues(12.dp),
  zoomInPainter: Painter = ZoomButtonsDefaults.zoomInPainter(),
  zoomOutPainter: Painter = ZoomButtonsDefaults.zoomOutPainter(),
  getZoomInPosition: (CameraPosition) -> CameraPosition = { it.copy(zoom = it.zoom + 1) },
  getZoomOutPosition: (CameraPosition) -> CameraPosition = { it.copy(zoom = it.zoom - 1) },
) {
  val currentMapState = mapState
  val coroutineScope = rememberCoroutineScope()
  val zoomInInteractionSource = remember { MutableInteractionSource() }
  val zoomOutInteractionSource = remember { MutableInteractionSource() }
  val zoomInHovered by zoomInInteractionSource.collectIsHoveredAsState()
  val zoomOutHovered by zoomOutInteractionSource.collectIsHoveredAsState()
  val shadowElevation by
    animateDpAsState(
      if (zoomInHovered || zoomOutHovered) style.hoveredShadowElevation else style.shadowElevation
    )

  // Successive presses in one direction step from the target of the animation in flight, so three
  // quick presses zoom three levels instead of restarting each step from the camera mid-flight. A
  // press in the other direction, or after a gesture, steps from the camera instead: the engine may
  // have clamped the previous target to a constraint, and a gesture moves the camera elsewhere.
  var inFlight by remember { mutableStateOf<InFlightZoom?>(null) }
  LaunchedEffect(currentMapState.isCameraMoving, currentMapState.cameraMoveReason) {
    if (
      currentMapState.isCameraMoving && currentMapState.cameraMoveReason == CameraMoveReason.GESTURE
    ) {
      inFlight = null
    }
  }
  fun animateZoom(zoomIn: Boolean, getPosition: (CameraPosition) -> CameraPosition) {
    val from = inFlight?.takeIf { it.zoomIn == zoomIn }?.target ?: currentMapState.cameraPosition
    val request = InFlightZoom(zoomIn, getPosition(from))
    inFlight = request
    coroutineScope.launch {
      try {
        currentMapState.animateCameraPosition(request.target)
      } finally {
        if (inFlight === request) inFlight = null
      }
    }
  }

  Column(
    modifier
      .requiredWidth(width)
      .shadow(shadowElevation, style.shape, clip = false)
      .background(style.containerColor, style.shape)
      .clip(style.shape)
  ) {
    ZoomButton(
      onClick = {
        animateZoom(zoomIn = true, getZoomInPosition)
        onZoomIn()
      },
      interactionSource = zoomInInteractionSource,
      contentDescription = contentDescriptionZoomIn,
      painter = zoomInPainter,
      contentColor = style.contentColor,
      size = width,
      contentPadding = contentPadding,
    )
    Box(Modifier.fillMaxWidth().height(style.dividerThickness).background(style.dividerColor))
    ZoomButton(
      onClick = {
        animateZoom(zoomIn = false, getZoomOutPosition)
        onZoomOut()
      },
      interactionSource = zoomOutInteractionSource,
      contentDescription = contentDescriptionZoomOut,
      painter = zoomOutPainter,
      contentColor = style.contentColor,
      size = width,
      contentPadding = contentPadding,
    )
  }
}

private class InFlightZoom(val zoomIn: Boolean, val target: CameraPosition)

@Composable
private fun ZoomButton(
  onClick: () -> Unit,
  interactionSource: MutableInteractionSource,
  contentDescription: String,
  painter: Painter,
  contentColor: Color,
  size: Dp,
  contentPadding: PaddingValues,
) {
  Box(
    Modifier.requiredSize(size)
      .clickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(contentPadding),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      painter = painter,
      contentDescription = contentDescription,
      modifier = Modifier.fillMaxSize(),
      colorFilter = ColorFilter.tint(contentColor),
    )
  }
}

public object ZoomButtonsDefaults {
  /** Contrasts with both light and dark basemaps, in the absence of a theme to draw colors from. */
  public val ContainerColor: Color = Color.White.copy(alpha = 0.9f)

  /** Contrasts with [ContainerColor], in the absence of a theme to draw colors from. */
  public val ContentColor: Color = Color.Black.copy(alpha = 0.75f)

  public val DividerColor: Color = ContentColor.copy(alpha = 0.2f)

  public val DividerThickness: Dp = 1.dp

  public val ShadowElevation: Dp = 0.dp

  public val HoveredShadowElevation: Dp = 0.dp

  /** Fully rounded ends, which make the container a vertical pill. */
  public val Shape: Shape = RoundedCornerShape(percent = 50)

  /** Accessibility label for the zoom-in button. */
  @Composable public fun contentDescriptionZoomIn(): String = stringResource(Res.string.zoom_in)

  /** Accessibility label for the zoom-out button. */
  @Composable public fun contentDescriptionZoomOut(): String = stringResource(Res.string.zoom_out)

  /** The plus icon that the zoom-in button draws. */
  @Composable public fun zoomInPainter(): Painter = painterResource(Res.drawable.add)

  /** The minus icon that the zoom-out button draws. */
  @Composable public fun zoomOutPainter(): Painter = painterResource(Res.drawable.remove)

  public fun style(): ZoomButtonsStyle = ZoomButtonsStyle()
}

@Immutable
public data class ZoomButtonsStyle(
  /** Color of the container behind the buttons. */
  public val containerColor: Color = ZoomButtonsDefaults.ContainerColor,

  /** Color of the button icons. */
  public val contentColor: Color = ZoomButtonsDefaults.ContentColor,

  /** Color of the divider between the buttons. */
  public val dividerColor: Color = ZoomButtonsDefaults.DividerColor,

  /** Thickness of the divider between the buttons. */
  public val dividerThickness: Dp = ZoomButtonsDefaults.DividerThickness,

  /** Shadow elevation of the container at rest. */
  public val shadowElevation: Dp = ZoomButtonsDefaults.ShadowElevation,

  /** Shadow elevation of the container while a pointer hovers over a button. */
  public val hoveredShadowElevation: Dp = ZoomButtonsDefaults.HoveredShadowElevation,

  /** Shape of the container. */
  public val shape: Shape = ZoomButtonsDefaults.Shape,
)
