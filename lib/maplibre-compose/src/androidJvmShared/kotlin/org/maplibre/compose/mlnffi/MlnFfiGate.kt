package org.maplibre.compose.mlnffi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
