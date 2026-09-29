package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.WakeSource

/** Parks in the native pump until a wake arrives, rather than on a bound. */
private const val PUMP_PARK_MILLIS = -1L

/**
 * One dedicated thread that owns one MapLibre runtime: it opens the runtime, runs posted tasks,
 * parks in the native pump between them, and tears everything down on the same thread.
 *
 * A runtime belongs to the thread that created it, and there may be only one per thread, so this
 * uses a dedicated [MlnFfiOwnerThread] rather than a dispatcher or a pooled executor.
 * maplibre-native-ffi#631 proposes an owner thread inside the C API, which would retire this class.
 *
 * A parked pump ignores interruption, so [stop] is the only way to end the thread.
 */
internal class MlnFfiRuntimeThread(
  private val name: String,
  private val getLogger: () -> MapLog?,
  private val openRuntime: () -> MlnFfiRuntimeOwner,
  /** Bounds one native drain; a negative value drains without a bound. */
  private val pumpBudgetMillis: Long,
  private val host: Host,
) {
  /**
   * What the owner of the runtime does at each step. Everything here runs on the owner thread,
   * except [stopReason] for a thread stopped before it started.
   */
  interface Host {
    /** Runs once, before any task. Throwing ends the thread as a failure. */
    fun onStarted(runtime: RuntimeHandle) {}

    /** Runs after every pump. Throwing ends the thread as a failure. */
    fun afterPump(runtime: RuntimeHandle)

    /**
     * Runs once when opening the runtime, [onStarted], the pump, or [afterPump] threw. [runtime] is
     * null when opening failed. Returns the error queued tasks are rejected with.
     */
    fun onLoopFailure(error: Throwable, runtime: RuntimeHandle?): Throwable

    /** The error queued tasks are rejected with when the thread stops normally or unstarted. */
    fun stopReason(): Throwable

    /**
     * Runs once, after queued tasks were rejected and before the runtime closes. [runtime] is null
     * when opening failed. Release failures go into [failures].
     */
    fun onStopping(runtime: RuntimeHandle?, failures: MutableList<Throwable>) {}

    /** Reports every release failure, by throwing, once everything was released. */
    fun reportCleanup(failures: List<Throwable>)
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
  private val thread = MlnFfiOwnerThread(name) { completion.complete(runCatching { runBody() }) }

  /**
   * Guards [tasks], [accepting], [started], and [wake] together: nothing may be queued after the
   * final drain, and nothing may signal a wake source that is closing.
   */
  private val acceptLock = MlnFfiLock()
  private val tasks = ArrayDeque<Task>()
  private var accepting = true

  /**
   * Set by [start]. Until then [stop] or [rejectQueuedTasksBeforeStart], not the owner, rejects.
   */
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
   * once, and not after [stop]. When [startThread] throws, queued tasks are rejected with its
   * error, [stop] and [awaitStopped] have nothing to wait for, and the error is rethrown.
   */
  fun start(startThread: (MlnFfiOwnerThread) -> Unit = MlnFfiOwnerThread::start) {
    acceptLock.withLock {
      check(!started) { "$name was already started" }
      check(accepting) { "$name was stopped" }
      started = true
    }
    try {
      startThread(thread)
    } catch (error: Throwable) {
      // No owner body will run to reject queued work. There are no native resources to release.
      rejectQueuedTasks(error, mutableListOf())
      completion.complete(Result.success(Unit))
      throw error
    }
  }

  /** Whether [start] has been called, whether or not the thread then started. */
  val isStarted: Boolean
    get() = acceptLock.withLock { started }

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
   * Rejects every queued task with [reason] and keeps accepting work, so a host that is not ready
   * to start yet can still start later. Only before [start]: afterwards the owner owns the queue.
   */
  fun rejectQueuedTasksBeforeStart(reason: Throwable) {
    val rejected = acceptLock.withLock {
      check(!started) { "The owner of $name owns its queue" }
      tasks.toList().also { tasks.clear() }
    }
    rejected.forEach { runCatching { it.reject(reason) } }
  }

  /**
   * Refuses new work and releases a parked pump. Returns at once; see [awaitStopped]. A thread that
   * never started has no runtime to release: this rejects its queued tasks with [Host.stopReason]
   * instead, and [awaitStopped] returns at once.
   */
  fun stop() {
    stopRequested = true
    // A signal, not a queued task: it still works after the accept gate closes; post would not.
    val unstarted = acceptLock.withLock {
      accepting = false
      wake?.signal()
      !started
    }
    if (unstarted) {
      rejectQueuedTasks(host.stopReason(), mutableListOf())
      completion.complete(Result.success(Unit))
    }
  }

  /** Completes after the owner thread released the runtime, throwing what cleanup reported. */
  suspend fun awaitStopped() = completion.await().getOrThrow()

  private fun runBody() {
    val failures = mutableListOf<Throwable>()
    val owner =
      try {
        openRuntime()
      } catch (error: Throwable) {
        rejectQueuedTasks(host.onLoopFailure(error, null), failures)
        host.onStopping(null, failures)
        host.reportCleanup(failures)
        return
      }
    val runtime = owner.runtime
    var failure: Throwable? = null
    try {
      host.onStarted(runtime)
      // Owner-thread affine (validated natively), so this cannot be hoisted into start().
      val source = runtime.acquireWakeSource()
      acceptLock.withLock { wake = source }
      while (!stopRequested) {
        // Queued work first: a task posted before the source was published set no wake flag.
        val ranTasks = runTasks(runtime)
        if (stopRequested) break
        // A batch that ran must not park: a task queuing nothing for native has nothing to wake it.
        // Never pump holding acceptLock: a parked pump would block the post that could wake it.
        runtime.pump(if (ranTasks) 0L else PUMP_PARK_MILLIS, pumpBudgetMillis)
        host.afterPump(runtime)
      }
    } catch (error: Throwable) {
      failure = host.onLoopFailure(error, runtime)
    } finally {
      rejectQueuedTasks(failure ?: host.stopReason(), failures)
      host.onStopping(runtime, failures)
      // Last, and only after its children: the provider retires before the runtime.
      runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
      host.reportCleanup(failures)
    }
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
        getLogger()?.e(error) { "A task on $name failed" }
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
