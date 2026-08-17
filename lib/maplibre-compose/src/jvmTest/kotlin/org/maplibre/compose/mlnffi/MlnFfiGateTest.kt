package org.maplibre.compose.mlnffi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MlnFfiGateTest {
  @Test
  fun awaitUntilOpenIgnoresInterruptionUntilTheGateOpens() {
    val gate = MlnFfiGate()
    val started = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val returnedEarly = AtomicBoolean(false)
    val restoredInterrupt = AtomicBoolean(false)

    val waiter =
      thread(name = "gate-await-until-open") {
        started.countDown()
        gate.awaitUntilOpen()
        restoredInterrupt.set(Thread.currentThread().isInterrupted)
        finished.countDown()
      }

    assertTrue(started.await(5, TimeUnit.SECONDS))
    waiter.interrupt()
    assertFalse(
      finished.await(200, TimeUnit.MILLISECONDS),
      "awaitUntilOpen returned before the gate opened",
    )
    returnedEarly.set(finished.count == 0L)
    assertFalse(returnedEarly.get())

    gate.open()
    assertTrue(finished.await(5, TimeUnit.SECONDS))
    assertTrue(restoredInterrupt.get(), "the interrupt status should be restored after the wait")
  }

  @Test
  fun awaitReturnsWhenTheWaitingThreadIsInterrupted() {
    val gate = MlnFfiGate()
    val started = CountDownLatch(1)
    val finished = CountDownLatch(1)

    val waiter =
      thread(name = "gate-await") {
        started.countDown()
        gate.await()
        finished.countDown()
      }

    assertTrue(started.await(5, TimeUnit.SECONDS))
    waiter.interrupt()
    assertTrue(
      finished.await(5, TimeUnit.SECONDS),
      "await should return when the waiting thread is interrupted",
    )
  }
}
