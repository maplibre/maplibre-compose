package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import org.maplibre.compose.camera.CameraPosition

@JvmInline internal value class GestureToken(val value: Long)

/** What [mapInput] needs of a map. Distances are in logical pixels. */
internal interface GestureTarget {
  fun cancelTransitions()

  fun getCameraPosition(): CameraPosition

  /** The token identifies this gesture's camera calls, so one ending late cannot close a newer. */
  fun onGestureStarted(): GestureToken

  fun onGestureEnded(token: GestureToken)

  /** A zero [duration] is a jump. */
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

  /** Suspends until the map hands the camera back at the end of the transition. */
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

/** The receiver of the taps and long presses that [mapInput] recognizes. */
internal interface MapClickTarget {
  fun onPrimaryClick(offset: DpOffset)

  /** Stands in for the mobile SDKs' long press. */
  fun onSecondaryClick(offset: DpOffset)
}
