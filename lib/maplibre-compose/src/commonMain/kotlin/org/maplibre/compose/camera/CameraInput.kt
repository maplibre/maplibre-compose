package org.maplibre.compose.camera

import androidx.compose.ui.unit.DpOffset
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.internal.CameraInputAuthority
import org.maplibre.compose.camera.internal.CameraInputTakenOver
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputPanByAwaitingTransition
import org.maplibre.compose.camera.internal.inputRotateAndPitchBy
import org.maplibre.compose.camera.internal.inputRotateAndPitchByAwaitingTransition
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.camera.internal.inputScaleByAwaitingTransition
import org.maplibre.compose.interaction.CameraInputOrigin
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.internal.requireNonnegativeFinite
import org.maplibre.compose.map.MapState

/**
 * Takes camera authority on the currently attached viewport. The block runs in a child of the
 * caller's coroutine, with the caller's context. A newer camera owner cancels that child and this
 * call returns normally after cleanup. Caller cancellation and block failures propagate.
 *
 * Uses the viewport's current [MapInteractions] camera policy. Disabled components do nothing;
 * arguments still require valid finite values. [MapInteractions.None] supports this scope. Commands
 * add no automatic release momentum. Ordinary programmatic camera methods are separate.
 *
 * Normal completion drains accepted commands before returning. The scope cannot be reused after
 * completion, cancellation, or detachment. Same-state nesting, including A-to-B-to-A, throws before
 * taking authority. Different-state nesting is supported.
 *
 * @throws IllegalStateException if no presentable viewport is attached or this state is nested.
 */
public suspend fun MapState.withCameraInput(block: suspend CameraInputScope.() -> Unit) {
  val authority = gestureAuthority
  val context = currentCoroutineContext()
  context.ensureActive()
  val parents = context[CameraInputNesting]?.authorities.orEmpty()
  check(authority !in parents) { "withCameraInput cannot nest on the same MapState" }

  supervisorScope {
    val token =
      authority.acquire(requireReady = true).also { it.origin = CameraInputOrigin.External }
    val target = checkNotNull(token.target)

    val child =
      async(CameraInputNesting(parents + authority), start = CoroutineStart.LAZY) {
        var completedNormally = false
        try {
          if (token.acceptsCommands) CameraInputScope(target, token).block()
          completedNormally = currentCoroutineContext().isActive
        } finally {
          withContext(NonCancellable) {
            if (!completedNormally || token.isCancelled) target.cancelGesture(token)
            else target.onGestureEnded(token)
            target.awaitGestureEnded(token)
          }
        }
      }

    authority.registerJob(token, child)
    child.start()

    try {
      child.await()
    } catch (_: CameraInputTakenOver) {
      currentCoroutineContext().ensureActive()
    } finally {
      // A lazy child cancelled before start does not execute its finally block.
      if (!token.completion.isCompleted) {
        withContext(NonCancellable) {
          if (token.isCancelled || !child.isCompleted || child.isCancelled)
            target.cancelGesture(token)
          else target.onGestureEnded(token)
          target.awaitGestureEnded(token)
        }
      }
    }
  }
}

/** A camera scope whose screen deltas are dp and angular deltas are degrees. */
public class CameraInputScope
internal constructor(
  private val target: CameraInputTarget,
  private val token: CameraInputToken,
) {
  /** Enqueues a screen-space pan. A positive X/Y moves map content right/down. */
  public fun panBy(deltaX: Double, deltaY: Double) {
    validate(deltaX, deltaY)
    target.inputPanBy(deltaX, deltaY, gestureToken = token)
  }

  /** Enqueues a positive multiplicative scale. Null anchor preserves the padded camera target. */
  public fun scaleBy(scale: Double, anchor: DpOffset? = null) {
    validateScale(scale)
    validateAnchor(anchor)
    target.inputScaleBy(scale, anchor, gestureToken = token)
  }

  public fun rotateAndPitchBy(bearingDelta: Double, pitchDelta: Double, anchor: DpOffset? = null) {
    validate(bearingDelta, pitchDelta)
    validateAnchor(anchor)
    target.inputRotateAndPitchBy(bearingDelta, pitchDelta, anchor = anchor, gestureToken = token)
  }

  /** Enqueues an eased pan and waits until its transition releases the camera. */
  public suspend fun panByAwaitingTransition(deltaX: Double, deltaY: Double, duration: Duration) {
    validate(deltaX, deltaY)
    requireNonnegativeFinite(duration, "duration")
    target.inputPanByAwaitingTransition(deltaX, deltaY, duration, token)
  }

  public suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset? = null,
    duration: Duration,
  ) {
    validateScale(scale)
    validateAnchor(anchor)
    requireNonnegativeFinite(duration, "duration")
    target.inputScaleByAwaitingTransition(scale, anchor, duration, token)
  }

  public suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    anchor: DpOffset? = null,
  ) {
    validate(bearingDelta, pitchDelta)
    validateAnchor(anchor)
    requireNonnegativeFinite(duration, "duration")
    target.inputRotateAndPitchByAwaitingTransition(
      bearingDelta,
      pitchDelta,
      duration,
      token,
      anchor,
    )
  }

  private fun validate(first: Double, second: Double) {
    check(token.acceptsCommands) { "The camera input scope is no longer active" }
    require(first.isFinite() && second.isFinite()) { "Camera deltas must be finite" }
  }

  private fun validateAnchor(anchor: DpOffset?) {
    require(anchor == null || (anchor.x.value.isFinite() && anchor.y.value.isFinite())) {
      "Camera anchors must be finite"
    }
  }

  private fun validateScale(scale: Double) {
    validate(scale, 0.0)
    require(scale > 0.0) { "Scale must be positive" }
  }
}

private class CameraInputNesting(val authorities: Set<CameraInputAuthority>) :
  AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<CameraInputNesting>
}
