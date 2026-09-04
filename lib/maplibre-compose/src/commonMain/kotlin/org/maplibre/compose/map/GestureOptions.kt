package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Configures map interaction gestures.
 *
 * Companion presets cover the common cases. Construct [GestureOptions] with individual fields to
 * change pan, zoom, rotate, and tilt independently.
 */
@Immutable
public data class GestureOptions(
  val isDragPanEnabled: Boolean = true,
  val isDragRotateTiltEnabled: Boolean = true,
  /** Whether a two-finger pinch changes zoom on touch devices. */
  val isPinchZoomEnabled: Boolean = true,
  /** Whether turning two fingers changes bearing on touch devices. */
  val isTwoFingerRotateEnabled: Boolean = true,
  /** Whether moving two fingers vertically together changes pitch on touch devices. */
  val isTwoFingerTiltEnabled: Boolean = true,
  /** Whether tapping with two fingers zooms out by one level. */
  val isTwoFingerTapZoomEnabled: Boolean = true,
  val isScrollZoomEnabled: Boolean = true,
  val isDoubleClickZoomEnabled: Boolean = true,
  /** Whether double-tapping, holding, and dragging vertically changes zoom. */
  val isQuickZoomEnabled: Boolean = true,
  /** Whether releasing a sufficiently fast pan continues it with a decelerating fling. */
  val isFlingEnabled: Boolean = true,
  /** Whether releasing a sufficiently fast pinch continues its zoom. */
  val isPinchZoomVelocityEnabled: Boolean = true,
  /** Whether releasing a sufficiently fast two-finger rotation continues its bearing. */
  val isRotateVelocityEnabled: Boolean = true,
  val isKeyboardPanEnabled: Boolean = true,
  val isKeyboardZoomEnabled: Boolean = true,
  val isKeyboardRotateTiltEnabled: Boolean = true,

  /**
   * How far a mouse may move while pressed and still click rather than drag. Touch and stylus use a
   * 4 dp pan slop and a 7 dp scale slop.
   */
  val clickSlop: Dp = 3.dp,

  /** Zoom levels per keyboard step and per double click. */
  val zoomStep: Double = 1.0,

  /** Zoom levels per unit of scroll. One wheel notch is a unit; trackpads report fractions. */
  val scrollZoomStep: Double = 0.15,

  /** How long after the last scroll event the zoom keeps counting as one continuous gesture. */
  val scrollZoomHold: Duration = 200.milliseconds,

  /** Degrees of bearing per dp of horizontal drag. */
  val dragRotateDegreesPerDp: Double = 0.8,

  /** Degrees of pitch per dp of vertical drag; negative, so dragging up tilts up. */
  val dragPitchDegreesPerDp: Double = -0.5,

  /** Degrees of pitch per physical pixel of a two-finger vertical drag. */
  val twoFingerTiltDegreesPerPixel: Double = -0.1,

  /** Maximum zoom-level change for a quick-zoom drag spanning the viewport height. */
  val quickZoomMaxZoomChange: Double = 4.0,

  /** How far the camera pans per arrow key. */
  val keyboardPanStep: Dp = 100.dp,

  /** Degrees of bearing per shift and left or right. */
  val keyboardRotateStep: Double = 15.0,

  /** Degrees of pitch per shift and up or down. */
  val keyboardPitchStep: Double = 10.0,

  /**
   * How long a discrete input (arrow key, double click) takes to ease the camera. On Android, the
   * system animator duration scale multiplies the duration. A scale of zero jumps.
   */
  val animationDuration: Duration = 300.milliseconds,
) {
  public companion object Companion {
    /** The recommended configuration for most use cases. */
    public val Standard: GestureOptions = GestureOptions()

    /** Disable gestures for moving the camera position. Rotation, tilt and zoom are allowed */
    public val PositionLocked: GestureOptions =
      GestureOptions(isDragPanEnabled = false, isKeyboardPanEnabled = false)

    /** Disable gestures for rotation and tilt. Moving position and zoom are allowed */
    public val RotationLocked: GestureOptions =
      GestureOptions(
        isDragRotateTiltEnabled = false,
        isTwoFingerRotateEnabled = false,
        isTwoFingerTiltEnabled = false,
        isRotateVelocityEnabled = false,
        isKeyboardRotateTiltEnabled = false,
      )

    /** Disable all gestures except for zoom. */
    public val ZoomOnly: GestureOptions =
      GestureOptions(
        isDragPanEnabled = false,
        isKeyboardPanEnabled = false,
        isDragRotateTiltEnabled = false,
        isTwoFingerRotateEnabled = false,
        isTwoFingerTiltEnabled = false,
        isRotateVelocityEnabled = false,
        isKeyboardRotateTiltEnabled = false,
      )

    /** All gestures disabled. Useful if you want to have full control over the camera movement. */
    public val AllDisabled: GestureOptions =
      GestureOptions(
        isDragPanEnabled = false,
        isDragRotateTiltEnabled = false,
        isPinchZoomEnabled = false,
        isTwoFingerRotateEnabled = false,
        isTwoFingerTiltEnabled = false,
        isTwoFingerTapZoomEnabled = false,
        isScrollZoomEnabled = false,
        isDoubleClickZoomEnabled = false,
        isQuickZoomEnabled = false,
        isFlingEnabled = false,
        isPinchZoomVelocityEnabled = false,
        isRotateVelocityEnabled = false,
        isKeyboardPanEnabled = false,
        isKeyboardZoomEnabled = false,
        isKeyboardRotateTiltEnabled = false,
      )
  }
}
