@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable

/** Identifies the calling thread. Two calls return equal values only from the same thread. */
internal expect fun currentThreadIdentity(): Any

/**
 * Turns main-thread confinement off. Only the Compose test harnesses use it: they compose on one
 * thread and drive the test from another, so no single thread owns the map. It is not reachable
 * through the public API, which rejects [Dispatchers.Unconfined].
 */
internal object UnconfinedTestMain : CoroutineDispatcher() {
  override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

  override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
}

/**
 * Pins map state to the thread [dispatcher] runs on. The pin comes from the dispatcher itself: at
 * construction when the caller is already on it, otherwise from a dispatched block, so a caller on
 * some other thread can never become the main thread by being first.
 */
internal class MainThreadGuard(dispatcher: CoroutineDispatcher) {
  private val unconfined = dispatcher === UnconfinedTestMain
  private val thread = AtomicReference<Any?>(null)

  init {
    require(dispatcher !== Dispatchers.Unconfined) {
      "Map state needs a main dispatcher that runs on one thread. Dispatchers.Unconfined runs on " +
        "the calling thread instead. Pass the platform's main dispatcher or another " +
        "single-threaded dispatcher."
    }
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
