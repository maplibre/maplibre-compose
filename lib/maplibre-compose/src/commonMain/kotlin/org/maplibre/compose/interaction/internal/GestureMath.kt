package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.style.scaledBy
import org.maplibre.compose.style.systemAnimatorDurationScale

/** Thresholds and camera equations for [mapInput] pointer gestures. Distances are in dp. */
internal object GestureMath {
  const val ScaleStartSpanDp = 7.0
  /** Android `ViewConfiguration.getScaledDoubleTapSlop()`, used to pair two touch taps. */
  const val DoubleTapSlopDp = 100.0
  const val ScaleStartWhileRotatingDp = 75.0
  const val ShoveStartDp = 16.0
  const val TwoFingerTapSlopDp = 5.0
  const val TwoFingerTapTimeoutMillis = 150L
  const val RotateStartDegrees = 3.0
  // MapLibre GL JS uses 25 logical pixels of arc travel to reject pinch-induced angle noise.
  const val RotateStartWhileZoomingArcDp = 25.0
  const val ShoveMaxFingerAngleDegrees = 45.0
  const val PressureRatioThreshold = 0.67f

  /** 6 mm at 160 dpi: 6 / 25.4 * 160. */
  const val MinimumTwoFingerSpanDp = 37.79527559055118

  private const val ZoomRate = 0.65
  private const val MinimumScaleSpeedDpPerMillisecond = 0.6
  private const val MinimumAngledScaleSpeedDpPerMillisecond = 0.9
  private const val MaximumScaleVelocityZoomChange = 2.5
  // Decay the measured camera speed over the default momentum duration.
  private const val TransformDecayMillis = 600.0
  // Quartic displacement gives a cubic velocity decay: a fast initial slowdown and a gentle tail.
  // Its integral is initialVelocity * duration / 4.
  const val TransformDecayPower = 4

  /** Returns a multiplicative scale, not a zoom delta. */
  fun pinchScale(rawScale: Double): Double {
    if (!rawScale.isFinite() || rawScale <= 0.0) return 1.0
    val zoomDelta = ln(rawScale) / ln(PI / 2.0) * ZoomRate
    return 2.0.pow(zoomDelta)
  }

  /** Positive Y is down: dragging down zooms in and dragging up zooms out. */
  fun quickZoomDelta(
    displacementPixels: Double,
    viewportHeightPixels: Double,
    maximumZoomChange: Double,
  ): Double =
    if (viewportHeightPixels > 0.0) displacementPixels / viewportHeightPixels * maximumZoomChange
    else 0.0

  fun shouldStartScale(
    spanDeltaFromStartDp: Double,
    spanDeltaFromPreviousDp: Double,
    elapsedMillis: Long,
    rotationDeltaFromPreviousDegrees: Double,
    startSpanSlopDp: Double = ScaleStartSpanDp,
  ): Boolean {
    if (abs(spanDeltaFromStartDp) < startSpanSlopDp || elapsedMillis < 0L) return false
    // Quantized synthetic samples can move at one timestamp. Their rate is unknown.
    if (elapsedMillis == 0L) return true
    val speed = abs(spanDeltaFromPreviousDp) / elapsedMillis
    if (speed < MinimumScaleSpeedDpPerMillisecond) return false
    return abs(rotationDeltaFromPreviousDegrees) <= 0.4 ||
      speed >= MinimumAngledScaleSpeedDpPerMillisecond
  }

  fun shouldStartRotation(
    rotationFromStartDegrees: Double,
    rotationFromPreviousDegrees: Double,
    elapsedMillis: Long,
    startAngleDegrees: Double = RotateStartDegrees,
  ): Boolean {
    val cumulative = abs(rotationFromStartDegrees)
    if (cumulative < startAngleDegrees || elapsedMillis < 0L) return false
    if (elapsedMillis == 0L) return true
    val speed = abs(rotationFromPreviousDegrees) / elapsedMillis
    return speed >= 0.04 &&
      !(speed > 0.07 && cumulative < 5.0) &&
      !(speed > 0.15 && cumulative < 7.0) &&
      !(speed > 0.5 && cumulative < 15.0)
  }

  fun shouldStartShove(
    firstDisplacementDp: Offset,
    secondDisplacementDp: Offset,
    fingerAngleFromHorizontalDegrees: Double,
    startSlopDp: Double = ShoveStartDp,
  ): Boolean =
    abs((firstDisplacementDp.y + secondDisplacementDp.y) / 2) >= startSlopDp &&
      abs(firstDisplacementDp.y) > abs(firstDisplacementDp.x) &&
      abs(secondDisplacementDp.y) > abs(secondDisplacementDp.x) &&
      (firstDisplacementDp.y > 0) == (secondDisplacementDp.y > 0) &&
      abs(fingerAngleFromHorizontalDegrees) <= ShoveMaxFingerAngleDegrees

  /** Rejects a sudden pressure drop, which is usually a finger lift. */
  fun hasStablePressure(current: Float, previous: Float): Boolean =
    previous <= 0f || current / previous > PressureRatioThreshold

  data class Fling(
    val offsetXDp: Double,
    val offsetYDp: Double,
    val duration: Duration,
    val decayPower: Int = 2,
  ) {
    fun settleWith(transformDuration: Duration): Fling {
      // Preserve initial speed when changing decay curves, without increasing total travel.
      val distanceScale =
        minOf(1.0, transformDuration / duration * decayPower / TransformDecayPower)
      return copy(
        offsetXDp = offsetXDp * distanceScale,
        offsetYDp = offsetYDp * distanceScale,
        duration = transformDuration,
        decayPower = TransformDecayPower,
      )
    }
  }

  /**
   * Screen-space travel for a flick of this speed. Equal speeds produce equal offsets, whether or
   * not the camera is pitched. [mapInput] applies the offset in small `moveBy` steps.
   */
  fun fling(
    velocityXDpPerSecond: Double,
    velocityYDpPerSecond: Double,
    continuation: PanMomentum = PanMomentum(),
  ): Fling? {
    if (!continuation.enabled) return null
    val velocity = hypot(velocityXDpPerSecond, velocityYDpPerSecond)
    if (!velocity.isFinite() || velocity == 0.0 || velocity < continuation.minimumSpeedDpPerSecond)
      return null
    val durationMillis =
      ((velocity / 10.5 + continuation.baseTime.inWholeMilliseconds) * continuation.durationScale)
        .toLong()
    if (durationMillis <= 0L || !durationMillis.milliseconds.isFinite()) return null
    return Fling(
      offsetXDp = velocityXDpPerSecond * durationMillis * 0.28 / 1000.0,
      offsetYDp = velocityYDpPerSecond * durationMillis * 0.28 / 1000.0,
      duration = durationMillis.milliseconds,
    )
  }

  /**
   * Largest screen-space `moveBy` a fling applies in one call. A dropped frame can cover the whole
   * remaining offset; splitting it keeps each unprojection as small as a live drag step.
   */
  const val FlingMaxStepDp = 16.0

  /** Splits [offsetXDp], [offsetYDp] into steps no longer than [maxStepDp]. */
  fun forEachScreenSpaceStep(
    offsetXDp: Double,
    offsetYDp: Double,
    maxStepDp: Double = FlingMaxStepDp,
    apply: (deltaX: Double, deltaY: Double) -> Unit,
  ) {
    val distance = hypot(offsetXDp, offsetYDp)
    if (distance == 0.0) return
    val steps = if (maxStepDp <= 0.0) 1 else ceil(distance / maxStepDp).toInt().coerceAtLeast(1)
    val stepX = offsetXDp / steps
    val stepY = offsetYDp / steps
    repeat(steps) { apply(stepX, stepY) }
  }

  data class ScaleVelocity(val zoomDelta: Double, val duration: Duration)

  fun scaleVelocity(
    zoomLevelsPerSecond: Double,
    continuation: VelocityMomentum = VelocityMomentum(),
  ): ScaleVelocity? {
    if (!continuation.enabled || !zoomLevelsPerSecond.isFinite() || zoomLevelsPerSecond == 0.0)
      return null
    val duration = continuation.duration(TransformDecayMillis) ?: return null
    val zoomDelta = zoomLevelsPerSecond * duration.inWholeNanoseconds / 1e9 / TransformDecayPower
    return ScaleVelocity(
      zoomDelta.coerceIn(-MaximumScaleVelocityZoomChange, MaximumScaleVelocityZoomChange),
      duration,
    )
  }

  data class RotationVelocity(val bearingDelta: Double, val duration: Duration)

  fun rotationVelocity(
    degreesPerSecond: Double,
    continuation: VelocityMomentum = VelocityMomentum(),
  ): RotationVelocity? {
    if (!continuation.enabled || !degreesPerSecond.isFinite() || degreesPerSecond == 0.0)
      return null
    val duration = continuation.duration(TransformDecayMillis) ?: return null
    return RotationVelocity(
      degreesPerSecond * duration.inWholeNanoseconds / 1e9 / TransformDecayPower,
      duration,
    )
  }

  data class PitchVelocity(val pitchDelta: Double, val duration: Duration)

  /** Integrates a pitch speed that decays cubically to zero over the configured duration. */
  fun pitchVelocity(
    degreesPerSecond: Double,
    continuation: PitchMomentum = PitchMomentum(),
  ): PitchVelocity? {
    if (
      !continuation.enabled ||
        !degreesPerSecond.isFinite() ||
        degreesPerSecond == 0.0 ||
        abs(degreesPerSecond) < continuation.minimumSpeedDegreesPerSecond ||
        continuation.duration == Duration.ZERO
    )
      return null
    return PitchVelocity(
      degreesPerSecond * (continuation.duration.inWholeNanoseconds / 1e9 / TransformDecayPower),
      continuation.duration,
    )
  }

  private fun VelocityMomentum.duration(unscaledMillis: Double): Duration? {
    if (!unscaledMillis.isFinite() || durationScale == 0.0) return null
    val duration =
      (unscaledMillis.toLong().milliseconds * durationScale).coerceAtMost(maximumDuration)
    return duration.takeIf { it > Duration.ZERO }
  }
}

/** A zoom level is a doubling. */
internal fun zoomLevelsToScale(levelDelta: Double): Double = 2.0.pow(levelDelta)

internal fun InputConfiguration.scaledAnimationDuration(): Duration =
  animationDuration.scaledBy(systemAnimatorDurationScale())
