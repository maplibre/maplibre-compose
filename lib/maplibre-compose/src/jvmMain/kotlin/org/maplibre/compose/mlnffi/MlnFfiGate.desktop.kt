package org.maplibre.compose.mlnffi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A latch of one count.
 *
 * Interruption ends a wait here and restores the interrupt flag, so a host that interrupts one of
 * these threads sees the state it expects. The waiting code itself treats interruption as a reason
 * to stop waiting rather than to retry, which is the behavior every platform can offer:
 * Kotlin/Native has no thread interruption at all.
 */
internal actual class MlnFfiGate actual constructor() {
  private val latch = CountDownLatch(1)

  actual fun open() {
    latch.countDown()
  }

  actual fun await() {
    try {
      latch.await()
    } catch (interruption: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  actual fun await(timeoutMillis: Long): Boolean =
    try {
      latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (interruption: InterruptedException) {
      Thread.currentThread().interrupt()
      latch.count == 0L
    }
}
