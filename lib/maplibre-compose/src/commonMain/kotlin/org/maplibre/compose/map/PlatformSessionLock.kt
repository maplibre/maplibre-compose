package org.maplibre.compose.map

/**
 * A lock for common state that several threads mutate; a single-threaded platform skips locking.
 */
internal expect fun newSessionLock(): SessionLock

/** Identifies the calling thread so the effect drain can tell a reentrant call from a rival. */
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
