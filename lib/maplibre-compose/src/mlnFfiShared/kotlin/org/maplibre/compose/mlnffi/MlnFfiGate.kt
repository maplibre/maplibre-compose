package org.maplibre.compose.mlnffi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A one-shot gate, opened once and awaited by any number of threads.
 *
 * Opening a gate that is already open changes nothing, and a wait on an open gate returns at once.
 * Interruption ends a wait and restores the interrupt flag, so a host that interrupts one of these
 * threads sees the state it expects. A caller reads the state that the gate guards rather than
 * treating a returned wait as proof the gate opened.
 */
internal class MlnFfiGate {
  private val latch = CountDownLatch(1)

  /** Opens the gate and releases every waiter. */
  fun open() {
    latch.countDown()
  }

  /** Waits without a bound. */
  fun await() {
    try {
      latch.await()
    } catch (interruption: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  /** Waits up to [timeoutMillis], reporting whether the gate opened. */
  fun await(timeoutMillis: Long): Boolean =
    try {
      latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (interruption: InterruptedException) {
      Thread.currentThread().interrupt()
      latch.count == 0L
    }
}
