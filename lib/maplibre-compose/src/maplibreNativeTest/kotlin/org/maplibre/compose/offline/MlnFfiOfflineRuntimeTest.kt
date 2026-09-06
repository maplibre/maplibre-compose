@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.offline

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.TestLatch

class MlnFfiOfflineRuntimeTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimes = mutableListOf<MlnFfiOfflineRuntime>()

  @AfterTest
  fun cleanUp() {
    runtimes.forEach { it.shutdown() }
    runtimes.forEach {
      assertTrue(it.awaitStopped(RESPONSE_TIMEOUT_MILLIS), "offline runtime did not stop")
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

  /**
   * Posting after shutdown is refused, and refusing must not signal a closed wake source, which
   * throws rather than no-oping.
   */
  @Test
  fun posting_after_shutdown_is_refused_without_throwing() {
    val runtime = runtime().also { it.start() }
    runtime.shutdown()
    assertTrue(runtime.awaitStopped(RESPONSE_TIMEOUT_MILLIS), "the runtime should have stopped")

    assertFalse(
      runtime.post(task = {}, reject = {}),
      "a task posted after shutdown should be refused",
    )
  }

  private companion object {
    const val RESPONSE_TIMEOUT_MILLIS = 5_000L
  }
}
