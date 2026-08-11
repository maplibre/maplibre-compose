package org.maplibre.compose.mlnffi

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MlnFfiOwnerThreadTest {
  @Test
  fun timedJoinRestoresCallerInterrupt() {
    val releaseOwner = CountDownLatch(1)
    val owner = MlnFfiOwnerThread("owner-thread-test") { releaseOwner.await() }
    owner.start()

    try {
      Thread.currentThread().interrupt()

      assertFalse(owner.join(10_000))
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
      releaseOwner.countDown()
      assertTrue(owner.join(10_000))
    }
  }
}
