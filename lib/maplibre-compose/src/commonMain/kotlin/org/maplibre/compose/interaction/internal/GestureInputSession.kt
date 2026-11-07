package org.maplibre.compose.interaction.internal

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken

/** A recognized input group and all its continuation work share this camera lifetime. */
internal class GestureInputSession(
  private val parent: CoroutineScope,
  private val target: CameraInputTarget,
  val token: CameraInputToken = target.onGestureStarted(),
  onHaptic: ((HapticFeedbackType) -> Unit)? = null,
  private val onCancelled: () -> Unit = {},
) {
  private val work = Job(parent.coroutineContext[Job])
  val scope = CoroutineScope(parent.coroutineContext + work)
  private var ending = false
  private val feedback = onHaptic?.let { Channel<HapticFeedbackType>(Channel.CONFLATED) }

  init {
    token.registerJob(work)
    if (feedback != null && onHaptic != null) {
      token.setHapticFeedback { feedback.trySend(it) }
      // Outside [work], whose children [end] waits for: feedback lasts through the momentum.
      parent.launch {
        for (type in feedback) {
          if (token.acceptsFeedback) onHaptic(type)
        }
      }
    }
    work.invokeOnCompletion {
      feedback?.cancel()
      if (work.isCancelled) {
        target.cancelGesture(token)
        // Authority can be revoked from an engine callback. Explicit dispatch prevents a Main
        // immediate dispatcher from changing recognizer state inside that owner loop.
        val dispatcher =
          parent.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
            ?: Dispatchers.Main
        dispatcher.dispatch(parent.coroutineContext) {
          if (parent.isActive) parent.launch(start = CoroutineStart.UNDISPATCHED) { onCancelled() }
        }
      }
    }
  }

  /**
   * Finishes response work, then ends input with the momentum that [release] starts and the snaps
   * of the parts the input changed, and waits for them and the input's camera events. An engine
   * that [CameraInputTarget.runsMomentum] continues the gesture itself; otherwise the momentum runs
   * here, frame by frame, before the input ends.
   */
  fun end(release: ReleaseVelocity? = null) {
    if (ending || work.isCancelled) return
    ending = true
    parent.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        work.children.toList().joinAll()
        if (work.isCancelled) return@launch
        val momentum = token.momentum(release)
        if (momentum != null && !target.runsMomentum) {
          withContext(scope.coroutineContext) { target.runMomentum(momentum, token) }
          target.onGestureEnded(token)
        } else {
          target.onGestureEnded(token, momentum)
        }
        withContext(NonCancellable) { token.awaitCompletion() }
      } catch (error: Throwable) {
        cancel()
        throw error
      } finally {
        feedback?.close()
        work.complete()
      }
    }
  }

  /** Revocation precedes coroutine cleanup, so queued camera commands cannot execute meanwhile. */
  fun cancel() {
    if (work.isCompleted) return
    feedback?.cancel()
    target.cancelGesture(token)
    work.cancel()
  }
}

/** A delayed tap starts a response only while its captured input generation is current. */
internal fun launchTapTransition(
  scope: CoroutineScope,
  target: CameraInputTarget,
  generation: Long,
  command: suspend CameraInputTarget.(CameraInputToken) -> Unit,
) {
  val token = target.onGestureStartedIfCurrent(generation) ?: return
  val session = GestureInputSession(scope, target, token)
  session.scope.launch {
    try {
      command(target, token)
    } finally {
      if (isActive) session.end() else session.cancel()
    }
  }
}
