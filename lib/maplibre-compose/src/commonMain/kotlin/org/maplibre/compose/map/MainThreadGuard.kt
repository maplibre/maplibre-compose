@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Identifies the calling thread. Two calls return equal values only from the same thread. */
internal expect fun currentThreadIdentity(): Any

/**
 * Pins map state to one thread: the one the runtime's main dispatcher runs on. The first thread to
 * check in becomes that thread, and every later check from another thread fails.
 */
internal class MainThreadGuard {
  private val thread = AtomicReference<Any?>(null)

  /** Records the calling thread as the main thread if none is known, then requires it. */
  fun requireMain() {
    val current = currentThreadIdentity()
    val known =
      thread.load() ?: current.also { thread.compareAndSet(null, it) }.let { thread.load() }
    check(known == current) {
      "Map state must be used from the main thread. This call came from another thread."
    }
  }
}
