package org.maplibre.compose.map

import androidx.compose.ui.unit.Density
import kotlin.math.sign
import org.maplibre.compose.input.PairMotion
import org.maplibre.compose.input.PairSample
import org.maplibre.compose.input.PointerTransformPolicy
import org.maplibre.compose.input.TransformComponent
import org.maplibre.compose.input.TransformDecision

/** Map fidelity rules: velocity-gated rotation/scale and exclusive two-contact vertical drag. */
internal class MapTransformPolicy(
  private val density: Density,
  private val pan: GestureBindingSettings?,
  private val pinch: GestureBindingSettings?,
  private val rotate: GestureBindingSettings?,
  private val shove: GestureBindingSettings?,
) : PointerTransformPolicy {
  private var rotationSpan = 0.0

  override fun reset(sample: PairSample) {
    rotationSpan = sample.distance
  }

  override fun accepts(previous: PairSample, current: PairSample): Boolean =
    GestureMath.hasStablePressure(current.pressure, previous.pressure)

  override fun needsRebase(previous: PairSample, current: PairSample): Boolean =
    current.distance < GestureMath.MINIMUM_TWO_FINGER_SPAN_DP * density.density ||
      previous.distance <= 0

  override fun recognize(motion: PairMotion, active: Set<TransformComponent>): TransformDecision {
    val rotating = TransformComponent.Rotation in active
    val shoving = TransformComponent.VerticalDrag in active
    val starts = linkedSetOf<TransformComponent>()
    val cancels = linkedSetOf<TransformComponent>()
    val current = motion.current
    val span = (current.distance - motion.origin.distance) * 2 / density.density
    val spanDelta = (current.distance - motion.previous.distance) * 2 / density.density
    val startRotate =
      motion.rotationFromStart != 0.0 &&
        rotate != null &&
        !rotating &&
        !shoving &&
        GestureMath.shouldStartRotation(
          motion.rotationFromStart,
          motion.rotation,
          motion.elapsed,
          rotate.startAngle,
        )
    val scaleSlop = pinch?.startSpanSlop?.value?.toDouble() ?: 0.0
    val scaleSpan = if (rotating) (current.distance - rotationSpan) * 2 / density.density else span
    val startPinch =
      scaleSpan != 0.0 &&
        pinch != null &&
        TransformComponent.Scale !in active &&
        !shoving &&
        GestureMath.shouldStartScale(
          scaleSpan,
          spanDelta,
          motion.elapsed,
          motion.rotation,
          if (rotating) maxOf(scaleSlop, GestureMath.SCALE_START_WHILE_ROTATING_DP) else scaleSlop,
        )
    val startShove =
      motion.displacement.y != 0f &&
        shove != null &&
        !shoving &&
        GestureMath.shouldStartShove(
          (motion.displacement.y / density.density).toDouble(),
          current.horizontalAngle,
          shove.startSlop.value.toDouble(),
        )
    var panDelta = motion.pan
    var scale = motion.scale
    var rotation = motion.rotation
    var vertical = motion.pan.y
    if (startRotate) {
      cancels += TransformComponent.Scale
      rotationSpan = current.distance
      rotation =
        motion.rotationFromStart - sign(motion.rotationFromStart) * checkNotNull(rotate).startAngle
      starts += TransformComponent.Rotation
    } else if (startPinch) {
      val threshold =
        if (rotating) maxOf(scaleSlop, GestureMath.SCALE_START_WHILE_ROTATING_DP) else scaleSlop
      val baseline = if (rotating) rotationSpan else motion.origin.distance
      scale = current.distance / (baseline + sign(scaleSpan) * threshold * density.density / 2)
      starts += TransformComponent.Scale
    } else if (startShove) {
      cancels +=
        listOf(TransformComponent.Pan, TransformComponent.Scale, TransformComponent.Rotation)
      vertical =
        motion.displacement.y -
          sign(motion.displacement.y) * checkNotNull(shove).startSlop.value * density.density
      starts += TransformComponent.VerticalDrag
    }
    if (
      !shoving &&
        TransformComponent.VerticalDrag !in starts &&
        pan != null &&
        TransformComponent.Pan !in active
    ) {
      val slop = pan.startSlop.value * density.density
      val distance = motion.displacement.getDistance()
      if (distance > 0 && distance >= slop) {
        panDelta = motion.displacement * ((distance - slop) / distance)
        starts += TransformComponent.Pan
      }
    }
    return TransformDecision(starts, cancels, panDelta, scale, rotation, vertical)
  }
}
