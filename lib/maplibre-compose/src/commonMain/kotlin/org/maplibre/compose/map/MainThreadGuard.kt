@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable

/** Identifies the calling thread. Two calls return equal values only from the same thread. */
internal expect fun currentThreadIdentity(): Any

/**
 * Pins map state to the thread [dispatcher] runs on. The pin comes from the dispatcher itself: at
 * construction when the caller is already on it, otherwise from a dispatched block, so a caller on
 * some other thread can never become the main thread by being first.
 *
 * [Dispatchers.Unconfined] has no thread, so it pins nothing and checks nothing. Test harnesses
 * whose Compose thread and test thread differ pass it on purpose; [platformMainDispatcher] never
 * produces it.
 */
internal class MainThreadGuard(dispatcher: CoroutineDispatcher) {
  private val unconfined = dispatcher === Dispatchers.Unconfined
  private val thread = AtomicReference<Any?>(null)

  init {
    when {
      unconfined -> Unit
      dispatcher.isDispatchNeeded(EmptyCoroutineContext) ->
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { pin() })
      else -> pin()
    }
  }

  /**
   * Records the calling thread as the main thread. Only the dispatcher's own execution calls it.
   */
  fun pin() {
    if (unconfined) return
    val current = currentThreadIdentity()
    thread.compareAndSet(null, current)
    check(thread.load() == current) { "The main dispatcher ran on two different threads" }
  }

  /** Fails unless the caller is on the main thread. */
  fun requireMain() {
    if (unconfined) return
    val known =
      checkNotNull(thread.load()) {
        "Map state was used before its main dispatcher ran. Let the dispatcher run first."
      }
    check(known == currentThreadIdentity()) {
      "Map state must be used from the main thread. This call came from another thread."
    }
  }
}
