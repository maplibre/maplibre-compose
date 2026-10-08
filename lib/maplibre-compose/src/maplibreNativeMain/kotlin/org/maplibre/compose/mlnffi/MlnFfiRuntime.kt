@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.compose.util.throwCleanupFailures
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventSourceType
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.WakeSource

/** Parks in the native pump until a wake arrives, rather than on a bound. */
private const val PumpParkMillis = -1L

/** Bounds native draining below a 120 Hz frame so queued input can run between pumps. */
private const val PumpBudgetMillis = 4L

/** One native runtime, owner thread, command queue and failure domain for a public MapRuntime. */
internal class MlnFfiRuntime(
  val options: MlnFfiRuntimeOptions,
  private val resourceConfig: MapResourceConfig =
    MapResourceConfig(options.requestInterceptor, options.resourceProvider, options.logger),
) {
  interface MapChild {
    fun onEvent(event: RuntimeEvent)

    fun onEventsDrained()

    fun onFailure(error: Throwable)

    /** Waits for renderer release, then destroys the map on its owner thread. */
    fun onStopping()
  }

  // Owner-thread registry. Retaining the map wrapper keeps event.mapSource resolvable.
  private val maps = mutableMapOf<MapHandle, MapChild>()
  var onFailure: (Throwable) -> Unit = {}
  var onOfflineEvent: (RuntimeHandle, RuntimeEvent) -> Unit = { _, _ -> }
  var onOfflineClosing: (Throwable) -> Unit = {}
  private val ready = CompletableDeferred<Result<Unit>>()
  @Volatile
  var failure: Throwable? = null
    private set

  fun initialized(result: Result<Unit>) {
    ready.complete(result)
  }

  suspend fun awaitReady() {
    ready.await().getOrThrow()
    failure?.let { throw it }
  }

  fun register(map: MapHandle, child: MapChild) {
    maps[map] = child
  }

  fun unregister(map: MapHandle) {
    maps.remove(map)
  }

  /**
   * Work for the owner thread. Exactly one of [run] and [reject] runs, unless [post] refused it.
   * Anything [run] throws is logged and passed to [reject]. [endsBatch] makes the thread pump and
   * drain events before it runs the next queued task.
   */
  class Task(
    val run: (RuntimeHandle) -> Unit,
    val reject: (Throwable) -> Unit,
    val endsBatch: Boolean = false,
  )

  private val completion = CompletableDeferred<Result<Unit>>()
  private val thread =
    MlnFfiOwnerThread("maplibre-compose-runtime") { completion.complete(runCatching { runBody() }) }

  /**
   * Guards [tasks], [accepting], [started], and [wake] together: nothing may be queued after the
   * final drain, and nothing may signal a wake source that is closing.
   */
  private val acceptLock = MlnFfiLock()
  private val tasks = ArrayDeque<Task>()
  private var accepting = true

  /** Set by [start]. Until then [close], not the owner, rejects. */
  private var started = false

  /**
   * Releases the owner thread from a parked pump. Acquired on that thread; signalled from any, but
   * always under [acceptLock] because signalling a closed source throws.
   */
  private var wake: WakeSource? = null

  @Volatile private var stopRequested = false

  /** Owner thread only. Set by [endBatch] to end the batch after the running task. */
  private var batchEndRequested = false

  /**
   * Starts the thread, which runs the tasks queued so far before any queued later. Called at most
   * once, and not after [close]. When [startThread] throws, queued tasks are rejected with its
   * error, [close] and [awaitClosed] have nothing to wait for, and the error is rethrown.
   */
  fun start(startThread: (MlnFfiOwnerThread) -> Unit = MlnFfiOwnerThread::start) {
    acceptLock.withLock {
      check(!started) { "MapLibre runtime was already started" }
      check(accepting) { "MapLibre runtime was stopped" }
      started = true
    }
    try {
      startThread(thread)
    } catch (error: Throwable) {
      // No owner body will run to reject queued work. There are no native resources to release.
      fail(error)
      rejectQueuedTasks(error, mutableListOf())
      onOfflineClosing(error)
      completion.complete(Result.success(Unit))
      throw error
    }
  }

  /** Whether the calling thread is the owner thread. */
  fun isCurrent(): Boolean = thread.isCurrent()

  /**
   * Owner thread only. Ends the running batch after the running task, as if it had
   * [Task.endsBatch], so work nested inside that task can let native deliver its events before
   * later queued work runs.
   */
  fun endBatch() {
    batchEndRequested = true
  }

  /**
   * Queues [task], reporting whether it was accepted. A refused task's [Task.reject] never runs.
   */
  fun post(task: Task): Boolean = acceptLock.withLock {
    if (!accepting) return false
    tasks.add(task)
    // Signalled under the lock so it cannot race the source's close, which would throw.
    wake?.signal()
    true
  }

  /**
   * Refuses new work and releases a parked pump. Returns at once; see [awaitClosed]. A thread that
   * never started has no runtime to release: this rejects its queued tasks with the closure error
   * instead, and [awaitClosed] returns at once.
   */
  fun close() {
    stopRequested = true
    // A signal, not a queued task: it still works after the accept gate closes; post would not.
    val unstarted = acceptLock.withLock {
      accepting = false
      wake?.signal()
      !started
    }
    if (unstarted) {
      val reason = stopReason()
      initialized(Result.failure(reason))
      rejectQueuedTasks(reason, mutableListOf())
      onOfflineClosing(reason)
      completion.complete(Result.success(Unit))
    }
  }

  /** Completes after the owner thread released the runtime, throwing what cleanup reported. */
  suspend fun awaitClosed() = completion.await().getOrThrow()

  private fun runBody() {
    val failures = mutableListOf<Throwable>()
    val owner =
      try {
        MlnFfiRuntimeOwner.open(
          options.cacheFile,
          { options.logger },
          "MapLibre runtime",
          options.resourceProviderFactory,
          resourceConfig,
        )
      } catch (error: Throwable) {
        fail(error)
        rejectQueuedTasks(error, failures)
        stopChildren(failures)
        failures.throwCleanupFailures()
        return
      }
    val runtime = owner.runtime
    try {
      // Owner-thread affine (validated natively), so this cannot be hoisted into start().
      val source = runtime.acquireWakeSource()
      acceptLock.withLock { wake = source }
      while (!stopRequested) {
        // Queued work first: a task posted before the source was published set no wake flag.
        val ranTasks = runTasks(runtime)
        if (stopRequested) break
        // A batch that ran must not park: a task queuing nothing for native has nothing to wake it.
        // Never pump holding acceptLock: a parked pump would block the post that could wake it.
        runtime.pump(if (ranTasks) 0L else PumpParkMillis, PumpBudgetMillis)
        for (event in runtime.drainEvents().events) {
          if (event.sourceType == RuntimeEventSourceType.MAP) {
            maps[event.mapSource]?.onEvent(event)
          } else onOfflineEvent(runtime, event)
        }
        maps.values.toList().forEach { it.onEventsDrained() }
      }
    } catch (error: Throwable) {
      fail(error)
    } finally {
      rejectQueuedTasks(failure ?: stopReason(), failures)
      stopChildren(failures)
      // Last, and only after its children: the provider retires before the runtime.
      runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
      failures.throwCleanupFailures()
    }
  }

  private fun stopReason(): Throwable = IllegalStateException("The map runtime is closed")

  private fun fail(error: Throwable) {
    failure = error
    initialized(Result.failure(error))
    options.logger?.e(error) { "The MapLibre runtime failed" }
    runCatching { onFailure(error) }
    maps.values.toList().forEach { it.onFailure(error) }
  }

  private fun stopChildren(failures: MutableList<Throwable>) {
    val reason = failure ?: stopReason()
    initialized(Result.failure(reason))
    runCatching { onOfflineClosing(reason) }.exceptionOrNull()?.let(failures::add)
    maps.values.toList().forEach { child ->
      runCatching { child.onStopping() }.exceptionOrNull()?.let(failures::add)
    }
    maps.clear()
  }

  /** Runs everything queued, up to a task that ends its batch, reporting whether anything ran. */
  private fun runTasks(runtime: RuntimeHandle): Boolean {
    var ran = false
    batchEndRequested = false
    while (true) {
      // Taken one at a time, and run outside the lock: a task posts, closes, and calls back.
      val task = acceptLock.withLock { tasks.removeFirstOrNull() } ?: break
      ran = true
      try {
        task.run(runtime)
      } catch (error: Throwable) {
        options.logger?.e(error) { "A task on MapLibre runtime failed" }
        // The task may have failed before reporting anything; a caller that already heard ignores
        // this.
        runCatching { task.reject(error) }
      }
      if (task.endsBatch || batchEndRequested) break
    }
    return ran
  }

  private fun rejectQueuedTasks(reason: Throwable, failures: MutableList<Throwable>) {
    // Stop accepting, drain the queue, and take the wake source out under one lock, so the drain
    // cannot race a task that would then never run and nothing can signal a source that is about to
    // close.
    val rejected = mutableListOf<Task>()
    val source = acceptLock.withLock {
      accepting = false
      rejected.addAll(tasks)
      tasks.clear()
      wake.also { wake = null }
    }
    // Rejected rather than dropped: a caller blocked on the task would otherwise never be released.
    rejected.forEach { runCatching { it.reject(reason) } }
    // A wake source is its own native handle, so closing the runtime does not release it.
    source?.let { closing -> runCatching { closing.close() }.exceptionOrNull()?.let(failures::add) }
  }
}
