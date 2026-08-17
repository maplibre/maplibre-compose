package org.maplibre.compose.mlnffi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A latch of one count.
 *
 * [await] ends on interruption and restores the interrupt flag, so a host that interrupts one of
 * these threads sees the state it expects. [awaitUntilOpen] keeps waiting until [open], then
 * restores the flag. Kotlin/Native has no thread interruption, so both waits are the same there.
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

  actual fun awaitUntilOpen() {
    var interrupted = false
    while (true) {
      try {
        latch.await()
        break
      } catch (interruption: InterruptedException) {
        interrupted = true
      }
    }
    if (interrupted) Thread.currentThread().interrupt()
  }

  actual fun await(timeoutMillis: Long): Boolean =
    try {
      latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (interruption: InterruptedException) {
      Thread.currentThread().interrupt()
      latch.count == 0L
    }
}
