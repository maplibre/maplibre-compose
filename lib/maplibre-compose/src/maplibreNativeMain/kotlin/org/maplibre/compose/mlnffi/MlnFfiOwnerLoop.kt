package org.maplibre.compose.mlnffi

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import org.maplibre.nativeffi.runtime.WakeSource

/**
 * The owner-thread machinery every MapLibre loop shares: one dedicated [MlnFfiOwnerThread], the
 * accept-gated task deque, the wake source that releases a parked pump, and the teardown drain.
 *
 * The subclass supplies the whole loop body as [runLoop] and its own task type [T]; this class only
 * queues, hands out, and drains tasks.
 *
 * No task may be queued after the final drain: [drainQueuedTasks] closes the accept gate, empties
 * the deque, and takes the wake source out under one [acceptLock] hold, so a concurrent [submit]
 * either lands before the drain and reaches the drain's abandon path, or observes the closed gate
 * and reports refusal.
 */
internal abstract class MlnFfiOwnerLoop<T : Any>(private val threadName: String) {

  /** The owner thread. A parked pump ignores interruption, so [requestStop] is the only way out. */
  private val thread = MlnFfiOwnerThread(threadName, ::runLoop)

  /**
   * Guards the task deque, the accept gate, and the wake source together. A subclass may guard its
   * own accept-scoped state under it, but never holds it across a pump; see [MlnFfiOwnerLock].
   */
  protected val acceptLock = MlnFfiOwnerLock(thread)

  private val tasks = ArrayDeque<T>()
  private var accepting = true

  /**
   * Releases the owner thread from a parked pump. Acquired on that thread; signalled from any, but
   * always under [acceptLock] because signalling a closed source throws.
   */
  private var wake: WakeSource? = null

  @Volatile private var stopFlag = false

  /** True once [requestStop] ran; the loop body polls it between pumps. */
  protected val stopRequested: Boolean
    get() = stopFlag

  /** The logger failures on the shared paths report to. */
  protected abstract val loopLogger: Logger?

  /** The whole loop; runs exactly once on the owner thread. */
  protected abstract fun runLoop()

  fun start() {
    thread.start()
  }

  /** Whether the calling thread is the owner thread. */
  protected fun isOwnerThread(): Boolean = thread.isCurrent()

  /** Waits up to [timeoutMillis] for the loop to finish, reporting whether it did. */
  fun awaitStopped(timeoutMillis: Long): Boolean = thread.join(timeoutMillis)

  /** Stops the loop with a signal, not a queued task: it works after the accept gate closes. */
  protected fun requestStop() {
    stopFlag = true
    acceptLock.withLock { wake?.signal() }
  }

  /** Publishes the owner thread's wake source so submitters can release a parked pump. */
  protected fun publishWakeSource(source: WakeSource) {
    acceptLock.withLock { wake = source }
  }

  /** Queues [task] for the owner thread, reporting whether the accept gate was still open. */
  protected fun submit(task: T): Boolean = acceptLock.withLock {
    if (!accepting) return false
    tasks.add(task)
    // Signalled under the lock so it cannot race the source's close, which would throw.
    wake?.signal()
    true
  }

  /** Takes one queued task to run outside the lock, or null when the deque is momentarily empty. */
  protected fun takeQueuedTask(): T? = acceptLock.withLock { tasks.removeFirstOrNull() }

  /**
   * The final drain, under one lock hold as the class doc requires; [abandon] then runs on each
   * drained task and the wake source closes outside it.
   */
  protected fun drainQueuedTasks(abandon: (T) -> Unit) {
    val abandoned = mutableListOf<T>()
    val source = acceptLock.withLock {
      accepting = false
      abandoned.addAll(tasks)
      tasks.clear()
      wake.also { wake = null }
    }
    // Abandoned rather than run: a caller blocked on a queued task must still be resumed.
    abandoned.forEach { task -> runCatching { abandon(task) } }
    // A wake source is its own native handle, so closing the runtime does not release it.
    source?.let { closing ->
      runCatching { closing.close() }
        .onFailure { loopLogger?.w(it) { "Failed to close the $threadName loop's wake source" } }
    }
  }
}
