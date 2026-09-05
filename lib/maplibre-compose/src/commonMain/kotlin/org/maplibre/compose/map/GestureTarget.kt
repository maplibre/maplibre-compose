package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

internal class GestureToken(
  val value: Long,
  private val authority: GestureCameraAuthority? = null,
  val attachment: MapAttachment? = null,
  val target: GestureTarget? = null,
) {
  internal enum class Status {
    Open,
    Sealed,
    Cancelled,
    Completed,
  }

  internal var status = Status.Open
  internal var job: Job? = null
  internal var finishQueued = false
  internal val completion = CompletableDeferred<Unit>()
  val acceptsCommands: Boolean
    get() =
      authority?.accepts(this, enqueue = true) ?: (status == Status.Open && job?.isActive != false)

  val canExecute: Boolean
    get() =
      authority?.accepts(this, enqueue = false)
        ?: ((status == Status.Open || status == Status.Sealed) && job?.isActive != false)

  val isCancelled: Boolean
    get() = authority?.isCancelled(this) ?: (status == Status.Cancelled)

  fun registerJob(value: Job) {
    if (authority != null) authority.registerJob(this, value)
    else {
      job = value
      if (status == Status.Cancelled) value.cancel()
    }
  }

  fun enqueue(action: () -> Unit): Boolean =
    authority?.enqueue(this, action)
      ?: run {
        if (!acceptsCommands) return false
        action()
        true
      }

  fun finish(cancelled: Boolean, enqueue: () -> Unit) {
    if (authority != null) authority.finish(this, cancelled, enqueue)
    else {
      if (status == Status.Completed) return
      status =
        if (cancelled) Status.Cancelled else if (status == Status.Open) Status.Sealed else status
      if (!finishQueued) {
        finishQueued = true
        enqueue()
      }
    }
  }

  fun cancel(): Boolean =
    authority?.cancel(this)
      ?: run {
        status = Status.Cancelled
        true
      }

  fun complete() {
    if (authority != null) authority.complete(this)
    else {
      status = Status.Completed
      completion.complete(Unit)
    }
  }
}

/** What [mapInput] needs of a map. Distances are in logical pixels. */
internal interface GestureTarget {
  /** Accepted input invalidates older asynchronous camera fallthrough, even before recognition. */
  fun observeInput(): Long = 0L

  val inputGeneration: Long
    get() = 0L

  fun onGestureStartedIfCurrent(generation: Long): GestureToken? =
    if (generation == inputGeneration) onGestureStarted() else null

  fun positionFromScreenLocation(offset: DpOffset): Position? = null

  fun boxZoomFit(rect: DpRect): BoxZoomFit? =
    boxZoomFit(rect, getCameraPosition(), ::positionFromScreenLocation)

  suspend fun fitBoundsAwaitingTransition(
    fit: BoxZoomFit,
    duration: Duration,
    gestureToken: GestureToken,
  ): Unit = error("This gesture target does not support bounds fitting")

  val isGestureReady: Boolean
    get() = true

  fun cancelGesture(token: GestureToken) {
    token.finish(cancelled = true) {
      onGestureEnded(token)
      token.complete()
    }
  }

  suspend fun awaitGestureEnded(token: GestureToken) = Unit

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
    anchor: DpOffset? = null,
  )
}

/** Supplies current subscriber demand and captures a recognized tap's application dispatch path. */
internal interface MapInteractionTarget {
  val capabilities: Set<TapFamily>
    get() = emptySet()

  fun capture(family: TapFamily): MapClickPath?

  val hoverRevision: Any
    get() = Unit

  fun captureHover(): HoverScene? =
    HoverScene(Unit, Unit, null, emptyList(), { true }) { _, _ -> false }
}
