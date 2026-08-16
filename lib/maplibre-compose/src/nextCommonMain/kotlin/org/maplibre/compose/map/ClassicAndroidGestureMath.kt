package org.maplibre.compose.map

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The gesture constants and camera equations used by MapLibre Android 13.5.0. Values expressed as
 * Android dimensions are converted to dp before they reach these helpers.
 */
internal object ClassicAndroidGestureMath {
  const val PAN_START_DP = 4.0
  const val SCALE_START_SPAN_DP = 7.0
  const val SCALE_START_WHILE_ROTATING_DP = 75.0
  const val SHOVE_START_DP = 16.0
  const val TWO_FINGER_TAP_SLOP_DP = 5.0
  const val TWO_FINGER_TAP_TIMEOUT_MILLIS = 150L
  const val ROTATE_START_DEGREES = 3.0
  const val SHOVE_MAX_FINGER_ANGLE_DEGREES = 20.0
  const val PRESSURE_RATIO_THRESHOLD = 0.67f

  /** Android uses a 6 mm minimum span on API 24 and later: 6 / 25.4 * 160 dp. */
  const val MINIMUM_TWO_FINGER_SPAN_DP = 37.79527559055118

  private const val ZOOM_RATE = 0.65
  private const val MINIMUM_SCALE_SPEED_DP_PER_MILLISECOND = 0.6
  private const val MINIMUM_ANGLED_SCALE_SPEED_DP_PER_MILLISECOND = 0.9
  private const val MINIMUM_SCALE_VELOCITY_DP_PER_SECOND = 225.0
  private const val SCALE_VELOCITY_RATIO_THRESHOLD_DP = 4e-3 * 0.29
  private const val ROTATE_VELOCITY_RATIO_THRESHOLD_DP = 2.2e-4 * 0.29
  private const val MAXIMUM_SCALE_VELOCITY_ZOOM_CHANGE = 2.5
  private const val ANGULAR_VELOCITY_MULTIPLIER_DP = 1.3
  private const val MINIMUM_ANGULAR_VELOCITY_DP = 0.1
  private const val MAXIMUM_ANGULAR_VELOCITY = 30.0
  private const val FLING_THRESHOLD_DP_PER_SECOND = 1000.0
  private const val FLING_BASE_TIME_MILLIS = 150.0
  private const val VELOCITY_ANIMATION_DURATION_MULTIPLIER = 150.0

  /** Returns a multiplicative scale, not a zoom delta. */
  fun pinchScale(rawScale: Double): Double {
    if (!rawScale.isFinite() || rawScale <= 0.0) return 1.0
    val zoomDelta = ln(rawScale) / ln(PI / 2.0) * ZOOM_RATE
    return 2.0.pow(zoomDelta)
  }

  /** Positive Y is down, so—as on Android—dragging down zooms in and dragging up zooms out. */
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
  ): Boolean {
    if (abs(spanDeltaFromStartDp) < SCALE_START_SPAN_DP || elapsedMillis <= 0L) return false
    val speed = abs(spanDeltaFromPreviousDp) / elapsedMillis
    if (speed < MINIMUM_SCALE_SPEED_DP_PER_MILLISECOND) return false
    return abs(rotationDeltaFromPreviousDegrees) <= 0.4 ||
      speed >= MINIMUM_ANGLED_SCALE_SPEED_DP_PER_MILLISECOND
  }

  fun shouldStartRotation(
    rotationFromStartDegrees: Double,
    rotationFromPreviousDegrees: Double,
    elapsedMillis: Long,
  ): Boolean {
    val cumulative = abs(rotationFromStartDegrees)
    if (cumulative < ROTATE_START_DEGREES || elapsedMillis <= 0L) return false
    val speed = abs(rotationFromPreviousDegrees) / elapsedMillis
    return speed >= 0.04 &&
      !(speed > 0.07 && cumulative < 5.0) &&
      !(speed > 0.15 && cumulative < 7.0) &&
      !(speed > 0.5 && cumulative < 15.0)
  }

  fun shouldStartShove(
    verticalDisplacementDp: Double,
    fingerAngleFromHorizontalDegrees: Double,
  ): Boolean =
    abs(verticalDisplacementDp) >= SHOVE_START_DP &&
      abs(fingerAngleFromHorizontalDegrees) <= SHOVE_MAX_FINGER_ANGLE_DEGREES

  /** Rejects the noisy coordinates Android observes while a finger is being lifted. */
  fun hasStablePressure(current: Float, previous: Float): Boolean =
    previous <= 0f || current / previous > PRESSURE_RATIO_THRESHOLD

  data class Fling(val offsetXDp: Double, val offsetYDp: Double, val duration: Duration)

  /**
   * The offset and duration MapLibre Android 13.5.0 applies as one `moveBy`. [pitch] shortens the
   * duration: a single unprojection of the whole offset travels farther toward the horizon than
   * away from it, and `pitch / 10` limits that jump.
   *
   * A caller that continues the drag in screen-space steps uses [screenSpaceFling] instead.
   */
  fun fling(velocityXDpPerSecond: Double, velocityYDpPerSecond: Double, pitch: Double): Fling? {
    val velocity = hypot(velocityXDpPerSecond, velocityYDpPerSecond)
    if (velocity < FLING_THRESHOLD_DP_PER_SECOND) return null
    val tiltFactor = 1.5 + if (pitch != 0.0) pitch / 10.0 else 0.0
    val durationMillis = (velocity / 7.0 / tiltFactor + FLING_BASE_TIME_MILLIS).toLong()
    return Fling(
      offsetXDp = velocityXDpPerSecond * durationMillis * 0.28 / 1000.0,
      offsetYDp = velocityYDpPerSecond * durationMillis * 0.28 / 1000.0,
      duration = durationMillis.milliseconds,
    )
  }

  /**
   * The [fling] offset for this speed with the Android tilt term omitted. Equal speeds produce
   * equal screen-space travel, whether or not the camera is pitched.
   */
  fun screenSpaceFling(velocityXDpPerSecond: Double, velocityYDpPerSecond: Double): Fling? =
    fling(velocityXDpPerSecond, velocityYDpPerSecond, pitch = 0.0)

  /**
   * Largest screen-space `moveBy` a fling applies in one call. A dropped frame can cover the whole
   * remaining offset; splitting it keeps each unprojection as small as a live drag step.
   */
  const val FLING_MAX_STEP_DP = 16.0

  /** Splits [offsetXDp], [offsetYDp] into steps no longer than [maxStepDp]. */
  fun forEachScreenSpaceStep(
    offsetXDp: Double,
    offsetYDp: Double,
    maxStepDp: Double = FLING_MAX_STEP_DP,
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
    velocityXPixelsPerSecond: Double,
    velocityYPixelsPerSecond: Double,
    spanSinceLastPixels: Double,
    density: Double,
    scalingOut: Boolean,
  ): ScaleVelocity? {
    val velocity = abs(velocityXPixelsPerSecond) + abs(velocityYPixelsPerSecond)
    if (velocity < MINIMUM_SCALE_VELOCITY_DP_PER_SECOND * density) return null
    if (spanSinceLastPixels / velocity < SCALE_VELOCITY_RATIO_THRESHOLD_DP * density) return null

    var zoomDelta =
      (velocity * MAXIMUM_SCALE_VELOCITY_ZOOM_CHANGE * 1e-4).coerceIn(
        0.0,
        MAXIMUM_SCALE_VELOCITY_ZOOM_CHANGE,
      )
    if (scalingOut) zoomDelta = -zoomDelta
    val durationMillis =
      (ln(abs(zoomDelta) + 1.0 / E.pow(2.0)) + 2.0) * VELOCITY_ANIMATION_DURATION_MULTIPLIER
    return ScaleVelocity(zoomDelta, durationMillis.toLong().milliseconds)
  }

  data class RotationVelocity(val initialDegreesPerFrame: Double, val duration: Duration)

  fun rotationVelocity(
    velocityXPixelsPerSecond: Double,
    velocityYPixelsPerSecond: Double,
    focalXPixel: Double,
    focalYPixel: Double,
    lastRotationDegrees: Double,
    density: Double,
    scaling: Boolean = false,
  ): RotationVelocity? {
    val denominator = focalXPixel * focalXPixel + focalYPixel * focalYPixel
    if (denominator <= 0.0) return null
    var angularVelocity =
      abs(
        (focalXPixel * velocityYPixelsPerSecond + focalYPixel * velocityXPixelsPerSecond) /
          denominator
      )
    if (lastRotationDegrees < 0.0) angularVelocity = -angularVelocity
    angularVelocity =
      (angularVelocity * ANGULAR_VELOCITY_MULTIPLIER_DP * density).coerceIn(
        -MAXIMUM_ANGULAR_VELOCITY,
        MAXIMUM_ANGULAR_VELOCITY,
      )
    if (abs(angularVelocity) < MINIMUM_ANGULAR_VELOCITY_DP * density) return null
    val velocity = abs(velocityXPixelsPerSecond) + abs(velocityYPixelsPerSecond)
    if (
      scaling &&
        velocity > 0.0 &&
        abs(lastRotationDegrees) / velocity < ROTATE_VELOCITY_RATIO_THRESHOLD_DP * density
    ) {
      return null
    }
    val durationMillis =
      (ln(abs(angularVelocity) + 1.0 / E.pow(2.0)) + 2.0) * VELOCITY_ANIMATION_DURATION_MULTIPLIER
    return RotationVelocity(angularVelocity, durationMillis.toLong().milliseconds)
  }
}
