package org.maplibre.compose.camera.internal

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import kotlin.time.Duration
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.BearingSnapping
import org.maplibre.spatialk.geojson.Position

/** Camera operations used by built-in map controls. Screen distances are dp. */
internal interface CameraInputTarget {
  /** Accepted input invalidates older asynchronous camera fallthrough, even before recognition. */
  fun observeInput(): Long

  val inputGeneration: Long

  fun onGestureStartedIfCurrent(generation: Long): CameraInputToken?

  fun positionFromScreenLocation(offset: DpOffset): Position?

  fun boxZoomFit(rect: DpRect): BoxZoomFit? =
    boxZoomFit(rect, getCameraPosition(), ::positionFromScreenLocation)

  suspend fun fitBoundsAwaitingTransition(
    fit: BoxZoomFit,
    duration: Duration,
    gestureToken: CameraInputToken,
  )

  /** Selects a target on the engine thread after queued movement, under the same camera token. */
  suspend fun snapBearingAwaitingTransition(
    snapping: BearingSnapping,
    duration: Duration,
    gestureToken: CameraInputToken,
  )

  val isGestureReady: Boolean

  /** Revokes accepted commands synchronously, then queues backend cancellation and its fence. */
  fun cancelGesture(token: CameraInputToken)

  /** Accepted presses revoke camera work before the drag crosses slop. */
  fun interruptCamera()

  fun getCameraPosition(): CameraPosition

  /** The token identifies this gesture's camera calls, so one ending late cannot close a newer. */
  fun onGestureStarted(): CameraInputToken

  fun onGestureEnded(token: CameraInputToken)

  /** A zero [duration] is a jump. */
  fun moveBy(
    deltaX: Double,
    deltaY: Double,
    duration: Duration = Duration.ZERO,
    gestureToken: CameraInputToken? = null,
  )

  fun scaleBy(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration = Duration.ZERO,
    gestureToken: CameraInputToken? = null,
  )

  fun rotateAndPitchBy(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration = Duration.ZERO,
    anchor: DpOffset? = null,
    gestureToken: CameraInputToken? = null,
    feedback: Boolean = false,
  )

  /** Suspends until the map hands the camera back at the end of the transition. */
  suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: CameraInputToken,
  )

  suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: CameraInputToken,
  )

  suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: CameraInputToken,
    anchor: DpOffset? = null,
  )
}
