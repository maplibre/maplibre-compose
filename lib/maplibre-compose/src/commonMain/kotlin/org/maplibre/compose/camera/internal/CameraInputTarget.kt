package org.maplibre.compose.camera.internal

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.CameraInputOrigin
import org.maplibre.compose.interaction.internal.CameraComponent
import org.maplibre.compose.map.MapAttachment
import org.maplibre.spatialk.geojson.Position

internal class CameraInputToken(
  val value: Long,
  private val authority: CameraInputAuthority,
  val attachment: MapAttachment? = null,
  val target: CameraInputTarget? = null,
) {
  internal enum class Status {
    Open,
    Sealed,
    Cancelled,
    Completed,
  }

  internal var inputOrigin = CameraInputOrigin.Drag
  internal var origin: CameraInputOrigin
    get() = authority.origin(this)
    set(value) {
      authority.setOrigin(this, value)
    }

  internal val startedComponents = mutableSetOf<CameraComponent>()

  internal fun permitted(component: CameraComponent): Boolean = authority.permitted(this, component)

  internal fun prepare(component: CameraComponent): Boolean = authority.prepare(this, component)

  internal fun rearm(component: CameraComponent) = authority.rearm(this, component)

  internal var status = Status.Open
  internal var job: Job? = null
  internal var finishQueued = false
  internal val completion = CompletableDeferred<Unit>()
  val acceptsCommands: Boolean
    get() = authority.accepts(this, enqueue = true)

  val canExecute: Boolean
    get() = authority.accepts(this, enqueue = false)

  val isCancelled: Boolean
    get() = authority.isCancelled(this)

  fun registerJob(value: Job) = authority.registerJob(this, value)

  fun enqueue(action: () -> Unit): Boolean = authority.enqueue(this, action)

  fun finish(cancelled: Boolean, enqueue: () -> Unit) = authority.finish(this, cancelled, enqueue)

  fun complete() = authority.complete(this)
}

/** Camera operations shared by recognized gestures and app-owned input. Screen distances are dp. */
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

  val isGestureReady: Boolean

  /** Revokes accepted commands synchronously, then queues backend cancellation and its fence. */
  fun cancelGesture(token: CameraInputToken)

  suspend fun awaitGestureEnded(token: CameraInputToken)

  fun cancelTransitions()

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
