package org.maplibre.compose.mlnffi

/**
 * A one-shot gate, opened once and awaited by any number of threads.
 *
 * Opening a gate that is already open changes nothing, and a wait on an open gate returns at once.
 * [await] and the timed wait may return before the gate opens when the host interrupts the waiting
 * thread, so those callers read the state that the gate guards rather than treating a returned wait
 * as proof the gate opened. [awaitUntilOpen] returns only after [open], and restores the interrupt
 * status afterward.
 *
 * An actual over a condition variable waits in a loop over the open flag, because such a wait
 * returns from a spurious wakeup as readily as from a signal.
 */
internal expect class MlnFfiGate() {
  /** Opens the gate and releases every waiter. */
  fun open()

  /** Waits without a bound. Interruption may end the wait before the gate opens. */
  fun await()

  /**
   * Waits until the gate opens. Interruption does not end the wait; the interrupt status is
   * restored when this returns.
   */
  fun awaitUntilOpen()

  /** Waits up to [timeoutMillis], reporting whether the gate opened. */
  fun await(timeoutMillis: Long): Boolean
}
