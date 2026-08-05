package org.maplibre.compose.offline

import co.touchlab.kermit.Logger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.resource.DesktopRuntimeOwner
import org.maplibre.nativeffi.runtime.OfflineOperationHandle
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.WakeSource

/**
 * How long a park lasts before the loop pumps regardless of any wake.
 *
 * The runtime's wake flag covers style, tile, offline, and resource responses, queued events, and
 * this class's own [WakeSource], and every one of those returns the pump immediately — so this is a
 * backstop rather than the cadence. It is bounded rather than indefinite because timers and ready
 * sockets set the flag only when they queue owner-thread work, so a download waiting out a retry
 * has nothing to signal with.
 */
private const val PUMP_PARK_MILLIS = 100L

/**
 * The thread that owns the offline manager's MapLibre runtime, and the only place native offline
 * calls happen.
 *
 * A runtime belongs to the thread that created it, there may be only one per thread, and that
 * thread may not be the AWT event thread, a coroutine dispatcher thread, or any other pooled thread
 * that something else might also put a runtime on. The offline manager therefore owns a thread of
 * its own rather than borrowing one; a map's renderer thread will not do, because offline
 * management outlives any particular map. Two runtimes may share one cache database — measured, not
 * assumed, by `SharedCacheDatabaseTest` — so this costs a thread, not correctness.
 */
internal class DesktopOfflineRuntime(
  private val options: DesktopRuntimeOptions,
  private val logger: Logger,
  private val onEvent: (RuntimeEvent) -> Unit,
  /**
   * Injectable so a test can park long enough that a missing wake fails rather than passes late.
   */
  private val parkMillis: Long = PUMP_PARK_MILLIS,
) {

  /** Work for the owner thread, with the failure path it must take if it never gets to run. */
  private class OwnerTask(val run: (RuntimeHandle) -> Unit, val reject: (Throwable) -> Unit)

  private class PendingOperation(
    val description: String,
    val handle: OfflineOperationHandle<*>,
    val complete: (RuntimeHandle, RuntimeEvent) -> Unit,
    val discard: (Throwable) -> Unit,
  )

  private val tasks = LinkedBlockingQueue<OwnerTask>()

  /**
   * Guards [accepting] and [wake] together, so that a task cannot be queued after the queue has
   * been drained for the last time, and a signal cannot race the wake source's close.
   */
  private val acceptLock = ReentrantLock()
  private var accepting = true

  /**
   * Releases the owner thread from a parked pump. Acquired on that thread; signalled from any.
   *
   * Held under [acceptLock] because signalling a *closed* source throws, so the only safe way to
   * retire it is to stop accepting and take it out in the same critical section.
   */
  private var wake: WakeSource? = null

  @Volatile private var stopRequested = false

  /** Owner-thread state. Never read or written from anywhere else. */
  private val pending = mutableMapOf<Long, PendingOperation>()

  /**
   * The runtime and everything retired before it. Owner-thread state; see [DesktopRuntimeOwner].
   */
  private var runtimeOwner: DesktopRuntimeOwner? = null

  private val thread =
    Thread(::runLoop, "maplibre-compose-offline").apply {
      // The pump must never keep a shutting-down application alive; disposal is what stops it.
      // A parking pump also ignores interruption, so shutdown() is the only way to stop this
      // thread — interrupting it does nothing.
      isDaemon = true
    }

  fun start() {
    thread.start()
  }

  /** Waits for the owner thread to finish, reporting whether it did. For tests and diagnostics. */
  fun awaitStopped(timeoutMillis: Long): Boolean {
    thread.join(timeoutMillis)
    return !thread.isAlive
  }

  /** Asks the owner thread to tear down. Returns immediately; nothing is awaited. */
  fun shutdown() {
    stopRequested = true
    // A signal rather than a queued task: it releases a parked pump without going through the
    // queue, and it still works once the accept gate has closed, where post would do nothing.
    acceptLock.withLock { wake?.signal() }
  }

  /**
   * Queues [task] for the owner thread.
   *
   * Returns false when the runtime is already gone, in which case [reject] is not called and the
   * caller reports the failure itself. Otherwise exactly one of [task] and [reject] runs.
   */
  fun post(task: (RuntimeHandle) -> Unit, reject: (Throwable) -> Unit): Boolean =
    acceptLock.withLock {
      if (!accepting) return false
      tasks.add(OwnerTask(task, reject))
      // Signalled under the lock so it cannot race the source's close, which would throw. This is
      // safe in the direction that matters: the owner thread never holds this lock across a pump.
      wake?.signal()
      true
    }

  /**
   * Records an operation to be completed when its `OFFLINE_OPERATION_COMPLETED` event names it.
   *
   * Must be called from the owner thread, with the handle the operation just returned.
   */
  fun register(
    description: String,
    handle: OfflineOperationHandle<*>,
    complete: (RuntimeHandle, RuntimeEvent) -> Unit,
    discard: (Throwable) -> Unit,
  ) {
    assertOwnerThread("register")
    pending[handle.id] = PendingOperation(description, handle, complete, discard)
  }

  /**
   * Forgets an operation whose caller no longer wants its result, closing the handle on the owner
   * thread. Safe to call from any thread, including from a cancellation handler.
   */
  fun discard(handle: OfflineOperationHandle<*>) {
    val posted =
      post(
        task = {
          pending.remove(handle.id)
          closeQuietly(handle, "a cancelled offline operation")
        },
        // Teardown already closed every outstanding handle.
        reject = {},
      )
    if (!posted) {
      logger.v { "Offline operation ${handle.id} was cancelled after its runtime closed" }
    }
  }

  private fun runLoop() {
    val runtime =
      try {
        DesktopRuntimeOwner.open(options, logger, "MapLibre offline runtime")
          .also { runtimeOwner = it }
          .runtime
      } catch (error: Throwable) {
        logger.e(error) { "Could not create the MapLibre runtime for offline management" }
        rejectQueuedTasks(
          OfflineManagerException(
            "The MapLibre offline runtime could not be created: " +
              (error.message ?: error::class.simpleName)
          )
        )
        return
      }

    val source =
      try {
        // Owner-thread affine: acquireWakeSource validates the thread natively, so this cannot be
        // hoisted into start().
        runtime.acquireWakeSource()
      } catch (error: Throwable) {
        logger.e(error) { "Could not acquire a wake source for the MapLibre offline runtime" }
        teardown(runtime)
        return
      }
    acceptLock.withLock { wake = source }

    try {
      while (!stopRequested) {
        // Queued work first. A task posted before the source was published set no wake flag, and
        // the pump below clears the flag before it drains, so checking the queue only after a pump
        // returns is what would leave such a task parked behind.
        runTasks(runtime)
        if (stopRequested) break
        check(!acceptLock.isHeldByCurrentThread) { "the pump must not run under acceptLock" }
        // The runtime makes no progress on its own: no event is delivered and no download advances
        // except inside a pump. Parking here rather than on the task queue is what lets native
        // work, a queued event, or a posted task all release the same wait.
        runtime.pump(parkMillis)
        drainEvents(runtime)
      }
    } catch (error: Throwable) {
      logger.e(error) { "The MapLibre offline runtime loop failed" }
    } finally {
      teardown(runtime)
    }
  }

  /** Drains events until the queue is momentarily empty. */
  private fun drainEvents(runtime: RuntimeHandle) {
    while (true) {
      val event =
        try {
          runtime.pollEvent() ?: break
        } catch (error: Throwable) {
          logger.e(error) { "Failed to poll a MapLibre offline runtime event" }
          break
        }
      if (runtimeOwner?.consumeEvent(event) == true) {
        // This loop's own bookkeeping, not a caller's operation.
      } else if (event.type == RuntimeEventType.OFFLINE_OPERATION_COMPLETED) {
        completeOperation(runtime, event)
      } else {
        runCatching { onEvent(event) }
          .onFailure { logger.e(it) { "Failed to handle offline event ${event.type}" } }
      }
    }
  }

  private fun completeOperation(runtime: RuntimeHandle, event: RuntimeEvent) {
    val payload = event.payload as? RuntimeEventPayload.OfflineOperationCompleted
    if (payload == null) {
      logger.w { "An offline operation completed without a payload naming it" }
      return
    }

    val operation = pending.remove(payload.operationId)
    if (operation == null) {
      // Expected after a cancellation: the caller discarded the operation before native finished.
      logger.v { "Ignoring the completion of unknown offline operation ${payload.operationId}" }
      return
    }

    try {
      operation.complete(runtime, event)
    } catch (error: Throwable) {
      logger.e(error) { "Failed to complete the offline operation to ${operation.description}" }
    } finally {
      closeQuietly(operation.handle, "the operation to ${operation.description}")
    }
  }

  /** Runs every queued task. */
  private fun runTasks(runtime: RuntimeHandle) {
    while (true) {
      runTask(runtime, tasks.poll() ?: break)
    }
  }

  private fun runTask(runtime: RuntimeHandle, task: OwnerTask) {
    try {
      task.run(runtime)
    } catch (error: Throwable) {
      logger.e(error) { "An offline runtime task failed" }
      // The task may have failed before it could report anything, so the caller is told here. A
      // caller that already heard ignores this.
      runCatching { task.reject(error) }
    }
  }

  private fun teardown(runtime: RuntimeHandle) {
    assertOwnerThread("teardown")
    val disposed =
      OfflineManagerException("The offline manager was disposed before the operation finished")

    rejectQueuedTasks(disposed)

    // Closing the runtime discards every queued event, so anything still waiting for its
    // OFFLINE_OPERATION_COMPLETED would wait forever. Each one is failed explicitly instead, and
    // nothing is awaited here.
    val outstanding = pending.values.toList()
    pending.clear()
    outstanding.forEach { operation ->
      // Handles close on the thread that owns them, which is this one.
      closeQuietly(operation.handle, "the operation to ${operation.description}")
      runCatching { operation.discard(disposed) }
        .onFailure { logger.e(it) { "Failed to cancel the operation to ${operation.description}" } }
    }

    // Last, and only after its children: the cache budget and the resource provider are retired
    // first, then the runtime, which a failed close leaves live and its thread unable to host
    // another one -- which is why this thread ends here rather than being reused. This used to
    // close the runtime directly and never quiesced the provider at all; see DesktopRuntimeOwner.
    runtimeOwner?.close()
    runtimeOwner = null
  }

  private fun rejectQueuedTasks(reason: Throwable) {
    // Stop accepting first, so the drain below cannot race a task that would then never run, and
    // take the wake source out in the same breath so nothing can signal one that is about to close.
    val source = acceptLock.withLock {
      accepting = false
      wake.also { wake = null }
    }
    val abandoned = mutableListOf<OwnerTask>()
    tasks.drainTo(abandoned)
    abandoned.forEach { runCatching { it.reject(reason) } }
    // A wake source is its own native handle and outlives its runtime, so closing the runtime does
    // not release it; the leak cleaner reports one that is dropped.
    source?.let { closing ->
      runCatching { closing.close() }
        .onFailure { logger.w(it) { "Failed to close the offline runtime's wake source" } }
    }
  }

  private fun closeQuietly(handle: OfflineOperationHandle<*>, what: String) {
    runCatching { handle.close() }.onFailure { logger.w(it) { "Failed to close $what" } }
  }

  private fun assertOwnerThread(operation: String) {
    check(Thread.currentThread() === thread) {
      "$operation must run on the offline runtime's own thread (${thread.name}), but ran on " +
        "${Thread.currentThread().name}. MapLibre enforces this natively, so the failure would " +
        "otherwise surface as a WrongThreadException at the FFI boundary."
    }
  }
}
