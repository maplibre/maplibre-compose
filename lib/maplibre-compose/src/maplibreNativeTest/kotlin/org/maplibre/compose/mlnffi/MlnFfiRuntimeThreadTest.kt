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
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.nativeffi.runtime.RuntimeHandle

class MlnFfiRuntimeThreadTest {

  init {
    FfiTestPlatform.initialize()
  }

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val threads = mutableListOf<MlnFfiRuntimeThread>()

  /** Owner-thread events in order. Guarded by [lock]. */
  private val lock = MlnFfiLock()
  private val events = mutableListOf<String>()

  private val stopReason = IllegalStateException("stopped")

  @AfterTest
  fun cleanUp() = runBlocking {
    threads.forEach { it.stop() }
    threads.forEach { withTimeout(TIMEOUT_MILLIS) { it.awaitStopped() } }
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  private fun record(event: String) = lock.withLock { events += event }

  private fun recorded(): List<String> = lock.withLock { events.toList() }

  private fun thread(): MlnFfiRuntimeThread =
    MlnFfiRuntimeThread(
        name = "test-runtime-thread",
        getLogger = { MapLog },
        openRuntime = { MlnFfiRuntimeOwner.open(cacheFile, { MapLog }, "test runtime") },
        pumpBudgetMillis = -1L,
        host =
          object : MlnFfiRuntimeThread.Host {
            override fun onStarted(runtime: RuntimeHandle) = record("started")

            override fun afterPump(runtime: RuntimeHandle) {}

            override fun onLoopFailure(error: Throwable, runtime: RuntimeHandle?): Throwable = error

            override fun stopReason(): Throwable = stopReason

            override fun onStopping(runtime: RuntimeHandle?, failures: MutableList<Throwable>) =
              record("stopping")

            override fun reportCleanup(failures: List<Throwable>) {
              failures.firstOrNull()?.let { throw it }
            }
          },
      )
      .also { threads += it }

  private fun task(
    name: String,
    endsBatch: Boolean = false,
    onRun: () -> Unit = {},
  ): MlnFfiRuntimeThread.Task =
    MlnFfiRuntimeThread.Task(
      run = {
        record(name)
        onRun()
      },
      reject = { record("$name rejected: ${it.message}") },
      endsBatch = endsBatch,
    )

  @Test
  fun tasks_posted_before_start_run_in_order_after_the_host_starts() {
    val thread = thread()
    val done = TestLatch(1)
    assertTrue(thread.post(task("first")))
    assertTrue(thread.post(task("second")))
    assertTrue(thread.post(task("third", onRun = done::countDown)))

    thread.start()

    assertTrue(done.await(TIMEOUT_MILLIS), "the queued tasks did not run")
    assertEquals(listOf("started", "first", "second", "third"), recorded())
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
    assertEquals(listOf("started", "ends batch", "next"), recorded())
  }

  @Test
  fun stop_rejects_queued_work_once_with_the_stop_reason_and_refuses_new_work() = runBlocking {
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

    thread.stop()
    assertFalse(thread.post(task("refused")))
    val stopped = async(start = CoroutineStart.UNDISPATCHED) { thread.awaitStopped() }
    assertFalse(stopped.isCompleted, "the owner still holds the runtime")
    release.countDown()
    withTimeout(TIMEOUT_MILLIS) { stopped.await() }

    assertEquals(
      listOf(
        "started",
        "blocking",
        "queued one rejected: stopped",
        "queued two rejected: stopped",
        "stopping",
      ),
      recorded(),
    )
  }

  @Test
  fun stopping_before_start_rejects_queued_work_and_refuses_to_start() = runBlocking {
    val thread = thread()
    assertTrue(thread.post(task("released before start")))
    thread.rejectQueuedTasksBeforeStart(IllegalStateException("not ready"))
    assertTrue(thread.post(task("queued")))

    thread.stop()
    withTimeout(TIMEOUT_MILLIS) { thread.awaitStopped() }

    assertFalse(thread.post(task("refused")))
    assertFailsWith<IllegalStateException> { thread.start() }
    assertEquals(
      listOf("released before start rejected: not ready", "queued rejected: stopped"),
      recorded(),
    )
  }

  private companion object {
    const val TIMEOUT_MILLIS = 5_000L
  }
}
