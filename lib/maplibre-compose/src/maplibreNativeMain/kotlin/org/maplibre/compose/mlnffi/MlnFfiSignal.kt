package org.maplibre.compose.mlnffi

/**
 * Wakes one thread that waits for native work, such as a renderer thread that waits for a frame
 * result. Signals coalesce: a wait returns once after any number of signals since the last wait.
 */
internal expect class MlnFfiSignal() {
  /** Wakes the waiter, or the next wait. Callable from any thread, including native callbacks. */
  fun signal()

  /** Waits until a signal arrives that no earlier wait consumed. */
  fun await()
}
