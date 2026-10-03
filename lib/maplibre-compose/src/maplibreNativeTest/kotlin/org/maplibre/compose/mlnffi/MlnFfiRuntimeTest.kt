package org.maplibre.compose.mlnffi

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MlnFfiRuntimeTest {

  init {
    FfiTestPlatform.initialize()
  }

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val threads = mutableListOf<MlnFfiRuntime>()

  /** Owner-thread events in order. Guarded by [lock]. */
  private val lock = MlnFfiLock()
  private val events = mutableListOf<String>()

  @AfterTest
  fun cleanUp() = runBlocking {
    threads.forEach { it.close() }
    threads.forEach { withTimeout(TIMEOUT_MILLIS) { it.awaitClosed() } }
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  private fun record(event: String) = lock.withLock { events += event }

  private fun recorded(): List<String> = lock.withLock { events.toList() }

  private fun thread(): MlnFfiRuntime =
    MlnFfiRuntime(MlnFfiRuntimeOptions(cacheFile, logger = null)).also { threads += it }

  private fun task(
    name: String,
    endsBatch: Boolean = false,
    onRun: () -> Unit = {},
  ): MlnFfiRuntime.Task =
    MlnFfiRuntime.Task(
      run = {
        record(name)
        onRun()
      },
      reject = { record("$name rejected") },
      endsBatch = endsBatch,
    )

  @Test
  fun tasks_posted_before_start_run_in_order() {
    val thread = thread()
    val done = TestLatch(1)
    assertTrue(thread.post(task("first")))
    assertTrue(thread.post(task("second")))
    assertTrue(thread.post(task("third", onRun = done::countDown)))

    thread.start()

    assertTrue(done.await(TIMEOUT_MILLIS), "the queued tasks did not run")
    assertEquals(listOf("first", "second", "third"), recorded())
  }

  /**
   * A task posted before the wake source exists sets no wake flag, so after a batch that ended
   * early the thread must pump without parking, or the rest of the queue waits for an unrelated
   * wake.
   */
  @Test
  fun a_batch_ending_task_leaves_later_tasks_to_run_without_another_wake() {
    val thread = thread()
    val done = TestLatch(1)
    assertTrue(thread.post(task("ends batch", endsBatch = true)))
    assertTrue(thread.post(task("next", onRun = done::countDown)))

    thread.start()

    assertTrue(done.await(TIMEOUT_MILLIS), "the task after the batch waited for another wake")
    assertEquals(listOf("ends batch", "next"), recorded())
  }

  @Test
  fun close_rejects_queued_work_once_and_refuses_new_work() = runBlocking {
    val thread = thread()
    val entered = TestLatch(1)
    val release = TestLatch(1)
    thread.start()
    // The blocking task ends its batch; later tasks of the same batch would still run.
    assertTrue(
      thread.post(
        task("blocking", endsBatch = true) {
          entered.countDown()
          check(release.await(TIMEOUT_MILLIS)) { "the test did not release the owner" }
        }
      )
    )
    assertTrue(entered.await(TIMEOUT_MILLIS))
    assertTrue(thread.post(task("queued one")))
    assertTrue(thread.post(task("queued two")))

    thread.close()
    assertFalse(thread.post(task("refused")))
    val stopped = async(start = CoroutineStart.UNDISPATCHED) { thread.awaitClosed() }
    assertFalse(stopped.isCompleted, "the owner still holds the runtime")
    release.countDown()
    withTimeout(TIMEOUT_MILLIS) { stopped.await() }

    assertEquals(
      listOf(
        "blocking",
        "queued one rejected",
        "queued two rejected",
      ),
      recorded(),
    )
  }

  @Test
  fun stopping_before_start_rejects_queued_work_and_refuses_to_start() = runBlocking {
    val thread = thread()
    assertTrue(thread.post(task("queued")))

    thread.close()
    withTimeout(TIMEOUT_MILLIS) { thread.awaitClosed() }

    assertFalse(thread.post(task("refused")))
    assertFailsWith<IllegalStateException> { thread.start() }
    assertEquals(
      listOf("queued rejected"),
      recorded(),
    )
  }

  private companion object {
    const val TIMEOUT_MILLIS = 5_000L
  }
}
