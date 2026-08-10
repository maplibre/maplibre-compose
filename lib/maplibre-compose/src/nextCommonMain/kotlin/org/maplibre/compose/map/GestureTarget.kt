package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import org.maplibre.compose.camera.CameraPosition

/** Monotonic identity that orders gesture begin, camera work, and deferred completion. */
@JvmInline internal value class GestureToken(val value: Long)

/**
 * What [mapInput] needs of a map. Deltas rather than absolute positions, because a gesture is a
 * stream of them and the camera may be moving underneath. Distances are in logical pixels.
 */
internal interface GestureTarget {
  fun cancelTransitions()

  fun getCameraPosition(): CameraPosition

  /**
   * The token identifies the gesture's camera calls, so one ending late cannot close a newer one.
   */
  fun onGestureStarted(): GestureToken

  fun onGestureEnded(token: GestureToken)

  fun onPrimaryClick(offset: DpOffset)

  /** Stands in for the mobile SDKs' long press: a mouse has no press-and-hold convention. */
  fun onSecondaryClick(offset: DpOffset)

  /** A zero [duration] is a jump, which is what a drag wants; a key press eases instead. */
  fun moveBy(
    deltaX: Double,
    deltaY: Double,
    duration: Duration = Duration.ZERO,
    gestureToken: GestureToken? = null,
  )

  fun scaleBy(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration = Duration.ZERO,
    gestureToken: GestureToken? = null,
  )

  fun rotateAndPitchBy(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration = Duration.ZERO,
    anchor: DpOffset? = null,
    gestureToken: GestureToken? = null,
  )

  /**
   * The three animated calls again, suspending until the map hands the camera back: a discrete
   * input stays attributed as a gesture for as long as the transition it started runs.
   */
  suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken,
  )

  suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken,
  )

  suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: GestureToken,
  )
}
