package org.maplibre.compose.map

/**
 * A lock for common state that several threads mutate; a single-threaded platform skips locking.
 */
internal expect fun newSessionLock(): SessionLock

/**
 * A stable identity for the calling thread. The effect drain compares these with referential
 * identity so a reentrant call parks instead of waiting on itself.
 */
internal expect fun currentThreadToken(): Any

/**
 * A one-shot idle signal for waiters of the effect drain. A single-threaded platform's await
 * returns at once because that platform never has a rival drainer.
 */
internal interface IdleGate {
  fun open()

  fun await()
}

internal expect fun newIdleGate(): IdleGate
