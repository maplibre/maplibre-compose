package org.maplibre.compose.mlnffi

/**
 * A one-shot gate, opened once and awaited by any number of threads.
 *
 * Opening a gate that is already open changes nothing, and a wait on an open gate returns at once.
 * A platform may also end a wait early when the host interrupts the waiting thread, so a caller
 * reads the state that the gate guards rather than treating a returned wait as proof the gate
 * opened.
 *
 * An actual over a condition variable waits in a loop over the open flag, because such a wait
 * returns from a spurious wakeup as readily as from a signal.
 */
internal expect class MlnFfiGate() {
  /** Opens the gate and releases every waiter. */
  fun open()

  /** Waits without a bound. */
  fun await()

  /** Waits up to [timeoutMillis], reporting whether the gate opened. */
  fun await(timeoutMillis: Long): Boolean
}
