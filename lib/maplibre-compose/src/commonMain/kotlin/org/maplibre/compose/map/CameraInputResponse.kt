package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import kotlin.time.Duration

/** Shared policy boundary between recognized/app input and raw backend camera commands. */
internal fun GestureTarget.inputPanBy(
  deltaX: Double,
  deltaY: Double,
  gestureToken: GestureToken?,
) {
  val token = gestureToken ?: return
  if ((deltaX != 0.0 || deltaY != 0.0) && token.prepare(CameraComponent.Pan))
    moveBy(deltaX, deltaY, gestureToken = token)
}

internal fun GestureTarget.inputScaleBy(
  scale: Double,
  anchor: DpOffset?,
  gestureToken: GestureToken?,
) {
  val token = gestureToken ?: return
  if (scale != 1.0 && token.prepare(CameraComponent.Zoom))
    scaleBy(scale, anchor.takeIf { token.permitted(CameraComponent.Pan) }, gestureToken = token)
}

internal fun GestureTarget.inputRotateAndPitchBy(
  bearingDelta: Double,
  pitchDelta: Double,
  anchor: DpOffset? = null,
  gestureToken: GestureToken?,
) {
  val token = gestureToken ?: return
  val bearing =
    if (bearingDelta != 0.0 && token.prepare(CameraComponent.Rotate)) bearingDelta else 0.0
  val pitch = if (pitchDelta != 0.0 && token.prepare(CameraComponent.Tilt)) pitchDelta else 0.0
  if (token.acceptsCommands && (bearing != 0.0 || pitch != 0.0))
    rotateAndPitchBy(
      bearing,
      pitch,
      anchor = anchor.takeIf { token.permitted(CameraComponent.Pan) },
      gestureToken = token,
    )
}

internal suspend fun GestureTarget.inputPanByAwaitingTransition(
  deltaX: Double,
  deltaY: Double,
  duration: Duration,
  gestureToken: GestureToken,
) {
  if ((deltaX != 0.0 || deltaY != 0.0) && gestureToken.prepare(CameraComponent.Pan))
    moveByAwaitingTransition(deltaX, deltaY, duration, gestureToken)
}

internal suspend fun GestureTarget.inputScaleByAwaitingTransition(
  scale: Double,
  anchor: DpOffset?,
  duration: Duration,
  gestureToken: GestureToken,
) {
  if (scale != 1.0 && gestureToken.prepare(CameraComponent.Zoom))
    scaleByAwaitingTransition(
      scale,
      anchor.takeIf { gestureToken.permitted(CameraComponent.Pan) },
      duration,
      gestureToken,
    )
}

internal suspend fun GestureTarget.inputRotateAndPitchByAwaitingTransition(
  bearingDelta: Double,
  pitchDelta: Double,
  duration: Duration,
  gestureToken: GestureToken,
  anchor: DpOffset? = null,
) {
  val bearing =
    if (bearingDelta != 0.0 && gestureToken.prepare(CameraComponent.Rotate)) bearingDelta else 0.0
  val pitch =
    if (pitchDelta != 0.0 && gestureToken.prepare(CameraComponent.Tilt)) pitchDelta else 0.0
  if (gestureToken.acceptsCommands && (bearing != 0.0 || pitch != 0.0))
    rotateAndPitchByAwaitingTransition(
      bearing,
      pitch,
      duration,
      gestureToken,
      anchor.takeIf { gestureToken.permitted(CameraComponent.Pan) },
    )
}

internal suspend fun GestureTarget.inputFitBoundsAwaitingTransition(
  fit: BoxZoomFit,
  duration: Duration,
  gestureToken: GestureToken,
) {
  if (gestureToken.permitted(CameraComponent.Pan) && gestureToken.permitted(CameraComponent.Zoom))
    fitBoundsAwaitingTransition(fit, duration, gestureToken)
}
