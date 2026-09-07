package org.maplibre.compose.interaction.internal

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
  private val onCancelled: () -> Unit = {},
) {
  private val work = Job(parent.coroutineContext[Job])
  val scope = CoroutineScope(parent.coroutineContext + work)
  private var ending = false

  init {
    token.registerJob(work)
    work.invokeOnCompletion {
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

  /** Finish response work, then seal camera commands and wait for the backend to drain them. */
  fun end() {
    if (ending || work.isCancelled) return
    ending = true
    parent.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        work.children.toList().joinAll()
        if (work.isCancelled) return@launch
        target.onGestureEnded(token)
        withContext(NonCancellable) { token.awaitCompletion() }
      } finally {
        work.complete()
      }
    }
  }

  /** Revocation precedes coroutine cleanup, so queued camera commands cannot execute meanwhile. */
  fun cancel() {
    if (work.isCompleted) return
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
