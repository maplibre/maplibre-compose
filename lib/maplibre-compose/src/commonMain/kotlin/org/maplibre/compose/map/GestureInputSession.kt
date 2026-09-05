package org.maplibre.compose.map

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A recognized input group and all its continuation work share this camera lifetime. */
internal class GestureInputSession(
  private val parent: CoroutineScope,
  private val target: GestureTarget,
  val token: GestureToken = target.onGestureStarted(),
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
        // immediate dispatcher from invoking application input callbacks inside that owner loop.
        val dispatcher =
          parent.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
            ?: Dispatchers.Main
        dispatcher.dispatch(parent.coroutineContext) {
          if (parent.isActive) parent.launch(start = CoroutineStart.UNDISPATCHED) { onCancelled() }
        }
      }
    }
  }

  /** Seal synchronously, keeping the registered job alive until accepted commands have drained. */
  fun end() {
    if (ending || work.isCancelled) return
    ending = true
    target.onGestureEnded(token)
    parent.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        withContext(NonCancellable) { target.awaitGestureEnded(token) }
      } finally {
        work.complete()
      }
    }
  }

  /** Revocation precedes coroutine cleanup, so queued camera commands cannot execute meanwhile. */
  fun cancel() {
    if (work.isCompleted) return
    token.cancel()
    target.cancelGesture(token)
    work.cancel()
  }
}
