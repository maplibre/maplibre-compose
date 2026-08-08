package org.maplibre.compose.offline

import co.touchlab.kermit.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.FfiTestPlatform

/**
 * The offline runtime's owner thread parks in MapLibre's pump and is released by a wake.
 *
 * Every test here uses a one-minute park, so a wake that is missing or signalled somewhere it
 * cannot be seen fails the test rather than passing late.
 */
class MlnFfiOfflineRuntimeTest {

  private val cachePath = FfiTestPlatform.createCachePath()

  private val runtimes = mutableListOf<MlnFfiOfflineRuntime>()

  @AfterTest
  fun cleanUp() {
    runtimes.forEach { it.shutdown() }
    FfiTestPlatform.deleteCachePath(cachePath)
  }

  private fun startRuntime(): MlnFfiOfflineRuntime =
    MlnFfiOfflineRuntime(
        cachePath = cachePath,
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

    val ran = CountDownLatch(1)
    assertTrue(runtime.post(task = { ran.countDown() }, reject = {}), "the task should be accepted")

    assertTrue(
      ran.await(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
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
    val ran = CountDownLatch(1)
    // No delay between start() and post(): the point is to race the acquisition.
    runtime.post(task = { ran.countDown() }, reject = {})

    assertTrue(
      ran.await(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
      "A task posted during startup was left parked behind instead of drained before the park.",
    )
  }

  @Test
  fun `a cancelled task waiting in the queue does not run`() {
    val runtime = startRuntime()
    val blockerStarted = CountDownLatch(1)
    val releaseBlocker = CountDownLatch(1)
    runtime.post(
      task = {
        blockerStarted.countDown()
        releaseBlocker.await()
      },
      reject = {},
    )
    assertTrue(
      blockerStarted.await(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
      "the blocking task never reached the owner thread",
    )

    val cancelled = AtomicBoolean(false)
    val ran = AtomicBoolean(false)
    val queueDrained = CountDownLatch(1)
    runtime.post(task = { ran.set(true) }, reject = {}, isCancelled = cancelled::get)
    runtime.post(task = { queueDrained.countDown() }, reject = {})

    cancelled.set(true)
    releaseBlocker.countDown()
    assertTrue(
      queueDrained.await(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
      "the owner thread did not drain the queued work",
    )
    assertFalse(ran.get(), "the cancelled task must not run")
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
    val warmedUp = CountDownLatch(1)
    post(task = { warmedUp.countDown() }, reject = {})
    assertTrue(
      warmedUp.await(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
      "the loop never ran its first task",
    )
    Thread.sleep(PARK_ENTRY_MILLIS)
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
