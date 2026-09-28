@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.offline

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.TestLatch

class MlnFfiOfflineRuntimeTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimes = mutableListOf<MlnFfiOfflineRuntime>()

  @AfterTest
  fun cleanUp() = runBlocking {
    runtimes.forEach { it.shutdown() }
    runtimes.forEach {
      withTimeout(RESPONSE_TIMEOUT_MILLIS) { it.awaitClosed() }
    }
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  private fun runtime(): MlnFfiOfflineRuntime =
    MlnFfiOfflineRuntime(
        cacheFile = cacheFile,
        logger = MapLog,
        onEvent = {},
      )
      .also {
        runtimes += it
      }

  /**
   * A task posted before the loop acquires its wake source sets no wake flag, so the loop has to
   * drain the queue before its first park.
   */
  @Test
  fun a_task_posted_before_the_wake_source_exists_still_runs_promptly() {
    val runtime = runtime()
    val ran = TestLatch(1)
    assertTrue(runtime.post(task = { ran.countDown() }, reject = {}))
    assertFalse(ran.await(0))
    runtime.start()

    assertTrue(
      ran.await(RESPONSE_TIMEOUT_MILLIS),
      "A task posted during startup was left parked behind instead of drained before the park.",
    )
  }

  @Test
  fun a_cancelled_task_waiting_in_the_queue_does_not_run() {
    val runtime = runtime()
    val cancelled = AtomicBoolean(false)
    val ran = AtomicBoolean(false)
    val queueDrained = TestLatch(1)
    runtime.post(task = { ran.store(true) }, reject = {}, isCancelled = cancelled::load)
    runtime.post(task = { queueDrained.countDown() }, reject = {})

    cancelled.store(true)
    runtime.start()
    assertTrue(
      queueDrained.await(RESPONSE_TIMEOUT_MILLIS),
      "the owner thread did not drain the queued work",
    )
    assertFalse(ran.load(), "the cancelled task must not run")
  }

  @Test
  fun shutdown_rejects_work_immediately_but_completion_waits_for_the_owner() = runBlocking {
    val runtime = runtime()
    val entered = TestLatch(1)
    val release = TestLatch(1)
    val releasedBeforeTimeout = CompletableDeferred<Boolean>()
    runtime.post(
      task = {
        entered.countDown()
        releasedBeforeTimeout.complete(release.await(RESPONSE_TIMEOUT_MILLIS))
      },
      reject = {},
    )
    runtime.start()
    try {
      assertTrue(entered.await(RESPONSE_TIMEOUT_MILLIS))
      runtime.shutdown()
      assertFalse(runtime.post(task = {}, reject = {}))
      val closed = async(start = CoroutineStart.UNDISPATCHED) { runtime.awaitClosed() }
      assertFalse(closed.isCompleted, "The owner still holds the runtime")
      release.countDown()
      assertTrue(releasedBeforeTimeout.await(), "Shutdown blocked until the owner timed out")
      withTimeout(RESPONSE_TIMEOUT_MILLIS) { closed.await() }
    } finally {
      release.countDown()
    }
  }

  private companion object {
    const val RESPONSE_TIMEOUT_MILLIS = 5_000L
  }
}
