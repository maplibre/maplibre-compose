package org.maplibre.compose.map

import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.DpOffset
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/** Camera access for application-owned input, including maps configured with [MapGestures.None]. */
@Stable
public class GestureCamera internal constructor(private val authority: GestureCameraAuthority) {
  /**
   * Takes camera authority on the currently attached viewport. The block runs in a child of the
   * caller's coroutine, with the caller's context. A newer camera owner cancels that child and this
   * call returns normally after cleanup. Caller cancellation and block failures propagate.
   *
   * Normal completion drains accepted commands before returning. The scope cannot be reused after
   * completion, cancellation, or detachment. Same-state nesting, including A-to-B-to-A, throws
   * before taking authority. Different-state nesting is supported.
   *
   * @throws IllegalStateException if no presentable viewport is attached or this state is nested.
   */
  public suspend fun withGesture(block: suspend GestureCameraScope.() -> Unit) {
    val context = currentCoroutineContext()
    context.ensureActive()
    val parents = context[GestureCameraNesting]?.authorities.orEmpty()
    check(authority !in parents) { "withGesture cannot nest on the same MapState" }
    supervisorScope {
      val token = authority.acquire(requireReady = true)
      val target = checkNotNull(token.target)
      val child =
        async(GestureCameraNesting(parents + authority), start = CoroutineStart.LAZY) {
          var completedNormally = false
          try {
            if (token.acceptsCommands) GestureCameraScope(target, token).block()
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
      } catch (_: GestureCameraTakenOver) {
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
}

/** A camera scope whose screen deltas are dp and angular deltas are degrees. */
public class GestureCameraScope
internal constructor(
  private val target: GestureTarget,
  private val token: GestureToken,
) {
  /** Enqueues a screen-space pan. A positive X/Y moves map content right/down. */
  public fun moveBy(deltaX: Double, deltaY: Double) {
    validate(deltaX, deltaY)
    target.moveBy(deltaX, deltaY, gestureToken = token)
  }

  /** Enqueues a positive multiplicative scale. Null anchor preserves the padded camera target. */
  public fun scaleBy(scale: Double, anchor: DpOffset? = null) {
    validateScale(scale)
    target.scaleBy(scale, anchor, gestureToken = token)
  }

  public fun rotateAndPitchBy(bearingDelta: Double, pitchDelta: Double, anchor: DpOffset? = null) {
    validate(bearingDelta, pitchDelta)
    target.rotateAndPitchBy(bearingDelta, pitchDelta, anchor = anchor, gestureToken = token)
  }

  /** Enqueues an eased pan and waits until its transition releases the camera. */
  public suspend fun moveByAwaitingTransition(deltaX: Double, deltaY: Double, duration: Duration) {
    validate(deltaX, deltaY)
    requireNonnegativeFinite(duration, "duration")
    target.moveByAwaitingTransition(deltaX, deltaY, duration, token)
  }

  public suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset? = null,
    duration: Duration,
  ) {
    validateScale(scale)
    requireNonnegativeFinite(duration, "duration")
    target.scaleByAwaitingTransition(scale, anchor, duration, token)
  }

  public suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    anchor: DpOffset? = null,
  ) {
    validate(bearingDelta, pitchDelta)
    requireNonnegativeFinite(duration, "duration")
    target.rotateAndPitchByAwaitingTransition(bearingDelta, pitchDelta, duration, token, anchor)
  }

  private fun validate(first: Double, second: Double) {
    check(token.acceptsCommands) { "The gesture camera scope is no longer active" }
    require(first.isFinite() && second.isFinite()) { "Camera deltas must be finite" }
  }

  private fun validateScale(scale: Double) {
    validate(scale, 0.0)
    require(scale > 0.0) { "Scale must be positive" }
  }
}

private class GestureCameraNesting(val authorities: Set<GestureCameraAuthority>) :
  AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<GestureCameraNesting>
}

internal class GestureCameraTakenOver : CancellationException("A newer input owns the camera")

internal fun interface CameraCommandGuard {
  fun isValid(): Boolean
}

/** The lifecycle lock serializes admission with takeover and completion fences. */
internal class GestureCameraAuthority(private val owner: MapState) {
  private var nextId = 0L
  private var cameraGeneration = 0L
  private var inputGeneration = 0L
  private var active: GestureToken? = null

  val generation: Long
    get() = owner.lifecycle.serialized { inputGeneration }

  fun acquire(
    adapter: MapAdapter? = null,
    requireReady: Boolean = false,
    expectedInputGeneration: Long? = null,
  ): GestureToken {
    var previous: GestureToken? = null
    val token =
      owner.lifecycle.serialized {
        val attachment = owner.currentMapAttachment
        val target = attachment?.adapter as? GestureTarget
        val ready =
          attachment != null &&
            owner.isCurrent(attachment) &&
            attachment.viewport != null &&
            target?.isGestureReady == true &&
            (adapter == null || adapter === attachment.adapter) &&
            (expectedInputGeneration == null || expectedInputGeneration == inputGeneration)
        check(!requireReady || ready) { "withGesture requires an attached, presentable viewport" }
        val token = GestureToken(++nextId, this, attachment, target)
        if (!ready) {
          token.status = GestureToken.Status.Cancelled
          token.completion.complete(Unit)
          return@serialized token
        }
        previous = revokeLocked()
        cameraGeneration++
        inputGeneration++
        active = token
        token
      }
    previous?.let(::cancelOutsideLock)
    return token
  }

  /** A delayed click may acquire camera authority only if no newer accepted input intervened. */
  fun acquireIfCurrent(adapter: MapAdapter, generation: Long): GestureToken? =
    acquire(adapter, expectedInputGeneration = generation).takeIf { it.acceptsCommands }

  /** Even input with no camera response invalidates an older click's camera fallthrough. */
  fun observeInput(): Long = owner.lifecycle.serialized { ++inputGeneration }

  fun beginProgrammatic(job: Job? = null): CameraCommandGuard {
    var previous: GestureToken? = null
    val generation =
      owner.lifecycle.serialized {
        check(!owner.isClosed) { "The map state is closed" }
        previous = revokeLocked()
        inputGeneration++
        ++cameraGeneration
      }
    previous?.let(::cancelOutsideLock)
    return CameraCommandGuard {
      owner.lifecycle.serialized {
        !owner.isClosed && cameraGeneration == generation && job?.isActive != false
      }
    }
  }

  fun registerJob(token: GestureToken, job: Job) {
    val cancel =
      owner.lifecycle.serialized {
        token.job = job
        token.status == GestureToken.Status.Cancelled
      }
    if (cancel) job.cancel(GestureCameraTakenOver())
  }

  fun accepts(token: GestureToken, enqueue: Boolean): Boolean =
    owner.lifecycle.serialized {
      acceptsLocked(token, enqueue)
    }

  fun enqueue(token: GestureToken, action: () -> Unit): Boolean =
    owner.lifecycle.serialized {
      if (!acceptsLocked(token, enqueue = true)) return false
      action()
      true
    }

  fun isCancelled(token: GestureToken): Boolean =
    owner.lifecycle.serialized {
      token.status == GestureToken.Status.Cancelled
    }

  fun finish(token: GestureToken, cancelled: Boolean, enqueue: () -> Unit): Unit =
    owner.lifecycle.serialized {
      if (token.status == GestureToken.Status.Completed) return
      if (cancelled) {
        token.status = GestureToken.Status.Cancelled
        if (active === token) active = null
      } else if (token.status == GestureToken.Status.Open) token.status = GestureToken.Status.Sealed
      if (!token.finishQueued) {
        token.finishQueued = true
        enqueue()
      }
    }

  fun cancel(token: GestureToken): Boolean =
    owner.lifecycle.serialized {
      if (token.status == GestureToken.Status.Completed) return false
      token.status = GestureToken.Status.Cancelled
      if (active === token) active = null
      true
    }

  fun complete(token: GestureToken) =
    owner.lifecycle.serialized {
      if (token.status != GestureToken.Status.Cancelled)
        token.status = GestureToken.Status.Completed
      if (active === token) active = null
      token.completion.complete(Unit)
    }

  /**
   * Called while invalidating the attachment; cancellation callbacks run outside the owner loop.
   */
  fun detach(attachment: MapAttachment) {
    val token =
      owner.lifecycle.serialized {
        inputGeneration++
        active?.takeIf { it.attachment === attachment }?.also { revokeLocked() }
      } ?: return
    owner.runtime.physicalScope.launch { cancelOutsideLock(token) }
  }

  private fun acceptsLocked(token: GestureToken, enqueue: Boolean): Boolean =
    active === token &&
      token.attachment?.let(owner::isCurrent) == true &&
      token.target?.isGestureReady == true &&
      token.job?.isActive != false &&
      (if (enqueue) token.status == GestureToken.Status.Open
      else token.status == GestureToken.Status.Open || token.status == GestureToken.Status.Sealed)

  private fun revokeLocked(): GestureToken? = active?.also {
    it.status = GestureToken.Status.Cancelled
    active = null
  }

  private fun cancelOutsideLock(token: GestureToken) {
    owner.lifecycle.serialized { token.job }?.cancel(GestureCameraTakenOver())
    token.target?.cancelGesture(token)
  }
}
