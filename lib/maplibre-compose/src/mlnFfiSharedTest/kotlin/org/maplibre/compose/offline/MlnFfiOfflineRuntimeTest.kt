@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.offline

import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.mlnffi.parkForTest

/**
 * The offline runtime's owner thread parks in MapLibre's pump and is released by a wake.
 *
 * Every test here uses a one-minute park, so a wake that is missing or signalled somewhere it
 * cannot be seen fails the test rather than passing late.
 */
class MlnFfiOfflineRuntimeTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimes = mutableListOf<MlnFfiOfflineRuntime>()

  @AfterTest
  fun cleanUp() {
    runtimes.forEach { it.shutdown() }
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  private fun startRuntime(): MlnFfiOfflineRuntime =
    MlnFfiOfflineRuntime(
        cacheFile = cacheFile,
        logger = Logger.withTag("offline-runtime-test"),
        onEvent = {},
      )
      .also {
        runtimes += it
        it.start()
      }

  @Test
  fun a_task_posted_to_a_parked_loop_wakes_it() {
    val runtime = startRuntime()
    runtime.parkAfterWarmup()

    val ran = TestLatch(1)
    assertTrue(runtime.post(task = { ran.countDown() }, reject = {}), "the task should be accepted")

    assertTrue(
      ran.await(RESPONSE_TIMEOUT_MILLIS),
      "The task did not run within ${RESPONSE_TIMEOUT_MILLIS}ms, so the wake source is not what " +
        "released the pump.",
    )
  }

  /**
   * A task posted before the loop acquires its wake source sets no wake flag, so the loop has to
   * drain the queue before its first park.
   */
  @Test
  fun a_task_posted_before_the_wake_source_exists_still_runs_promptly() {
    val runtime = startRuntime()
    val ran = TestLatch(1)
    // No delay between start() and post(): the point is to race the acquisition.
    runtime.post(task = { ran.countDown() }, reject = {})

    assertTrue(
      ran.await(RESPONSE_TIMEOUT_MILLIS),
      "A task posted during startup was left parked behind instead of drained before the park.",
    )
  }

  @Test
  fun a_cancelled_task_waiting_in_the_queue_does_not_run() {
    val runtime = startRuntime()
    val blockerStarted = TestLatch(1)
    val releaseBlocker = TestLatch(1)
    runtime.post(
      task = {
        blockerStarted.countDown()
        releaseBlocker.await()
      },
      reject = {},
    )
    assertTrue(
      blockerStarted.await(RESPONSE_TIMEOUT_MILLIS),
      "the blocking task never reached the owner thread",
    )

    val cancelled = AtomicBoolean(false)
    val ran = AtomicBoolean(false)
    val queueDrained = TestLatch(1)
    runtime.post(task = { ran.store(true) }, reject = {}, isCancelled = cancelled::load)
    runtime.post(task = { queueDrained.countDown() }, reject = {})

    cancelled.store(true)
    releaseBlocker.countDown()
    assertTrue(
      queueDrained.await(RESPONSE_TIMEOUT_MILLIS),
      "the owner thread did not drain the queued work",
    )
    assertFalse(ran.load(), "the cancelled task must not run")
  }

  /** Shutdown signals the wake source directly, because the accept gate may already be closed. */
  @Test
  fun shutdown_wakes_a_parked_thread() {
    val runtime = startRuntime()
    runtime.parkAfterWarmup()

    runtime.shutdown()

    assertTrue(
      runtime.awaitStopped(RESPONSE_TIMEOUT_MILLIS),
      "The runtime thread did not stop within ${RESPONSE_TIMEOUT_MILLIS}ms.",
    )
  }

  /**
   * Runs one task and then waits for the loop to be inside its park. Without this the loop drains
   * the queue on its way to the first park and the wake is never exercised.
   */
  private fun MlnFfiOfflineRuntime.parkAfterWarmup() {
    val warmedUp = TestLatch(1)
    post(task = { warmedUp.countDown() }, reject = {})
    assertTrue(
      warmedUp.await(RESPONSE_TIMEOUT_MILLIS),
      "the loop never ran its first task",
    )
    parkForTest(PARK_ENTRY_MILLIS)
  }

  /**
   * Posting after shutdown is refused, and refusing must not signal a closed wake source, which
   * throws rather than no-oping.
   */
  @Test
  fun posting_after_shutdown_is_refused_without_throwing() {
    val runtime = startRuntime()
    runtime.shutdown()
    assertTrue(runtime.awaitStopped(RESPONSE_TIMEOUT_MILLIS), "the runtime should have stopped")

    assertFalse(
      runtime.post(task = {}, reject = {}),
      "a task posted after shutdown should be refused",
    )
  }

  private companion object {
    const val RESPONSE_TIMEOUT_MILLIS = 5_000L

    /** Enough for the loop to get from running a task to being inside the pump. */
    const val PARK_ENTRY_MILLIS = 250L
  }
}
