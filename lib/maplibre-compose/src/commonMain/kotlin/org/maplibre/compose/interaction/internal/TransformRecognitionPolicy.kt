package org.maplibre.compose.interaction.internal

import androidx.compose.ui.unit.Density
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sign

/** Map fidelity rules: velocity-gated rotation/scale and exclusive two-contact vertical drag. */
internal class TransformRecognitionPolicy(
  private val density: Density,
  private val pan: TransformPanBinding?,
  private val pinch: TransformZoomBinding?,
  private val rotate: TransformRotateBinding?,
  private val shove: TransformTiltBinding?,
) {
  private var rotationSpan = 0.0
  private var minimumRotationSpan = 0.0
  private var rotationOrigin: PairSample? = null
  private var zoomWasActive = false

  fun reset(sample: PairSample) {
    rotationSpan = sample.distance
    minimumRotationSpan = sample.distance
    rotationOrigin = sample
    zoomWasActive = false
  }

  fun accepts(previous: PairSample, current: PairSample): Boolean =
    GestureMath.hasStablePressure(current.pressure, previous.pressure)

  fun needsRebase(previous: PairSample, current: PairSample): Boolean =
    current.distance < GestureMath.MINIMUM_TWO_FINGER_SPAN_DP * density.density ||
      previous.distance <= 0

  fun recognize(motion: PairMotion, active: Set<CameraComponent>): TransformDecision {
    val zooming = CameraComponent.Zoom in active
    // A zoom that just ended supplies the new rotation origin, not the original finger down.
    if (zoomWasActive && !zooming && rotate?.allowDuringZoom == false)
      rotationOrigin = motion.previous
    zoomWasActive = zooming

    val rotationFromStart =
      PairMotion(rotationOrigin ?: motion.origin, motion.previous, motion.current).rotationFromStart
    val rotating = CameraComponent.Rotate in active
    val shoving = CameraComponent.Tilt in active
    val current = motion.current
    val spanFromStartDp = (current.distance - motion.origin.distance) * 2 / density.density
    val spanDeltaDp = (current.distance - motion.previous.distance) * 2 / density.density

    val scaleSlop = pinch?.startSpanSlop?.value?.toDouble() ?: 0.0
    val scaleSpan =
      if (rotating) (current.distance - rotationSpan) * 2 / density.density else spanFromStartDp
    val scaleThreshold =
      if (rotating) maxOf(scaleSlop, GestureMath.SCALE_START_WHILE_ROTATING_DP) else scaleSlop
    val startPinch =
      scaleSpan != 0.0 &&
        pinch != null &&
        CameraComponent.Zoom !in active &&
        !shoving &&
        GestureMath.shouldStartScale(
          scaleSpan,
          spanDeltaDp,
          motion.elapsed,
          motion.rotation,
          scaleThreshold,
        )
    // A fixed angle becomes too sensitive as a pinch closes. Require enough arc travel
    // while zooming, using the smallest span so reopening the pinch cannot lower the threshold.
    minimumRotationSpan = min(minimumRotationSpan, current.distance)
    val rotationThreshold =
      if (zooming || startPinch)
        maxOf(
          rotate?.startAngle ?: 0.0,
          GestureMath.ROTATE_START_WHILE_ZOOMING_ARC_DP * density.density * 360.0 /
            (PI * minimumRotationSpan),
        )
      else rotate?.startAngle ?: 0.0

    // Rotation and scale use both displacement and speed to reject incidental finger motion.
    var startRotate =
      rotationFromStart != 0.0 &&
        rotate != null &&
        !rotating &&
        !shoving &&
        GestureMath.shouldStartRotation(
          rotationFromStart,
          motion.rotation,
          motion.elapsed,
          rotationThreshold,
        )

    val rotationBlockedByZoom = rotate?.allowDuringZoom == false && (zooming || startPinch)
    if (rotationBlockedByZoom) startRotate = false

    val startShove =
      shove != null &&
        !shoving &&
        !rotating &&
        !zooming &&
        GestureMath.shouldStartShove(
          (current.first - motion.origin.first) / density.density,
          (current.second - motion.origin.second) / density.density,
          current.horizontalAngle,
          shove.startSlop.value.toDouble(),
        )

    val starts = linkedSetOf<CameraComponent>()
    val cancels = linkedSetOf<CameraComponent>()
    var panDelta = motion.pan
    var scale = motion.scale
    var rotation = motion.rotation
    var vertical = motion.pan.y

    // Rebase while zoom owns the pair, so rotation cannot inherit the motion it rejected.
    if (rotationBlockedByZoom) {
      if (rotating) cancels += CameraComponent.Rotate
      rotationOrigin = current
    }

    // Tilt wins simultaneous recognition, but cannot interrupt established rotation or zoom.
    // Each first delta excludes the recognition threshold to avoid a visible camera jump.
    if (startShove) {
      cancels += CameraComponent.Pan
      vertical =
        motion.displacement.y -
          sign(motion.displacement.y) * checkNotNull(shove).startSlop.value * density.density
      starts += CameraComponent.Tilt
    } else if (startRotate) {
      cancels += CameraComponent.Zoom
      rotationSpan = current.distance
      rotation = rotationFromStart - sign(rotationFromStart) * rotationThreshold
      starts += CameraComponent.Rotate
    } else if (startPinch) {
      val baseline = if (rotating) rotationSpan else motion.origin.distance
      scale = current.distance / (baseline + sign(scaleSpan) * scaleThreshold * density.density / 2)
      starts += CameraComponent.Zoom
    }

    // Pan can accompany scale and rotation. Two-finger tilt owns the pair exclusively.
    if (
      !shoving && CameraComponent.Tilt !in starts && pan != null && CameraComponent.Pan !in active
    ) {
      val slop = pan.startSlop.value * density.density
      val distance = motion.displacement.getDistance()
      if (distance > 0 && distance >= slop) {
        panDelta = motion.displacement * ((distance - slop) / distance)
        starts += CameraComponent.Pan
      }
    }

    return TransformDecision(starts, cancels, panDelta, scale, rotation, vertical)
  }
}
