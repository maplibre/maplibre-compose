package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration
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

  /**
   * Splits [offsetXDp], [offsetYDp] into steps no longer than [maxStepDp], or into [maxSteps]
   * longer ones when that would take more. A dropped frame can cover a long offset; small steps
   * keep each unprojection as small as a live drag step.
   */
  fun forEachScreenSpaceStep(
    offsetXDp: Double,
    offsetYDp: Double,
    maxStepDp: Double,
    maxSteps: Int = Int.MAX_VALUE,
    apply: (deltaX: Double, deltaY: Double) -> Unit,
  ) {
    val distance = hypot(offsetXDp, offsetYDp)
    if (!(distance > 0.0)) return
    val steps =
      if (maxStepDp <= 0.0) 1
      else ceil(distance / maxStepDp).coerceAtMost(maxSteps.toDouble()).toInt().coerceAtLeast(1)
    val stepX = offsetXDp / steps
    val stepY = offsetYDp / steps
    repeat(steps) { apply(stepX, stepY) }
  }
}

/** A zoom level is a doubling. */
internal fun zoomLevelsToScale(levelDelta: Double): Double = 2.0.pow(levelDelta)

internal fun InputConfiguration.scaledAnimationDuration(): Duration =
  animationDuration.scaledBy(systemAnimatorDurationScale())
