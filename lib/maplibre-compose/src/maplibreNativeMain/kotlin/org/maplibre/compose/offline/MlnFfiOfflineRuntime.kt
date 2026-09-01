package org.maplibre.compose.offline

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.MlnFfiOwnerLock
import org.maplibre.compose.mlnffi.MlnFfiOwnerThread
import org.maplibre.compose.mlnffi.currentMlnFfiThreadName
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.nativeffi.runtime.OfflineOperationHandle
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.WakeSource

/**
 * Parks until a wake arrives, for the same reason the map loop does; see `MlnFfiMapRuntimeLoop`.
 */
private const val PUMP_PARK_MILLIS = -1L

/** Names the owner thread, and names it again in the message a wrong-thread call fails with. */
private const val OWNER_THREAD_NAME = "maplibre-compose-offline"

/**
 * The thread that owns the offline manager's MapLibre runtime, and the only place native offline
 * calls happen.
 *
 * A runtime belongs to the thread that created it, there may be only one per thread, and that
 * thread may not be the AWT event thread or any other pooled thread that something else might also
 * put a runtime on. Hence a dedicated thread rather than a borrowed one: offline management
 * outlives any particular map, so a map's renderer thread will not do. Two runtimes sharing one
 * cache database is safe, measured by `SharedCacheDatabaseTest`, so this costs a thread and not
 * correctness.
 */
internal class MlnFfiOfflineRuntime(
  private val cacheFile: Path,
  private val logger: Logger?,
  private val onEvent: (RuntimeEvent) -> Unit,
) {

  /** Work for the owner thread, with the failure path it must take if it never gets to run. */
  private class OwnerTask(
    val run: (RuntimeHandle) -> Unit,
    val reject: (Throwable) -> Unit,
    val isCancelled: () -> Boolean,
  )

  private class PendingOperation(
    val description: String,
    val handle: OfflineOperationHandle<*>,
    val complete: (RuntimeHandle, RuntimeEvent) -> Unit,
    val discard: (Throwable) -> Unit,
  )

  /**
   * The owner thread. A parked pump ignores interruption, and it must never keep a shutting-down
   * application alive, so [shutdown] is the only way to stop it.
   */
  private val thread = MlnFfiOwnerThread(OWNER_THREAD_NAME, ::runLoop)

  /**
   * Guards [tasks], [accepting], and [wake] together: no task may be queued after the final drain,
   * and no signal may race the wake source's close.
   */
  private val acceptLock = MlnFfiOwnerLock(thread)
  private val tasks = ArrayDeque<OwnerTask>()
  private var accepting = true

  /**
   * Releases the owner thread from a parked pump. Acquired on that thread; signalled from any, but
   * always under [acceptLock] because signalling a *closed* source throws.
   */
  private var wake: WakeSource? = null

  @Volatile private var stopRequested = false

  /** Owner-thread state. Never read or written from anywhere else. */
  private val pending = mutableMapOf<Long, PendingOperation>()

  /** The runtime and everything retired before it. Owner-thread state; see [MlnFfiRuntimeOwner]. */
  private var runtimeOwner: MlnFfiRuntimeOwner? = null

  fun start() {
    thread.start()
  }

  /** Waits for the owner thread to finish, reporting whether it did. For tests and diagnostics. */
  fun awaitStopped(timeoutMillis: Long): Boolean = thread.join(timeoutMillis)

  /** Asks the owner thread to tear down. Returns immediately; nothing is awaited. */
  fun shutdown() {
    stopRequested = true
    // A signal, not a queued task: it still works after the accept gate closes; post would not.
    acceptLock.withLock { wake?.signal() }
  }

  /**
   * Queues [task] for the owner thread.
   *
   * Returns false when the runtime is already gone, in which case [reject] is not called and the
   * caller reports the failure itself. Otherwise exactly one of [task] and [reject] runs, unless
   * [isCancelled] is true when the owner thread reaches it, in which case [reject] receives a
   * cancellation so resources reserved before posting can be released.
   */
  fun post(
    task: (RuntimeHandle) -> Unit,
    reject: (Throwable) -> Unit,
    isCancelled: () -> Boolean = { false },
  ): Boolean = acceptLock.withLock {
    if (!accepting) return false
    tasks.add(OwnerTask(task, reject, isCancelled))
    // Signalled under the lock so it cannot race the source's close, which would throw. The
    // owner thread never holds this lock across a pump.
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
          val operation = pending.remove(handle.id) ?: return@post
          closeQuietly(operation.handle, "a cancelled offline operation")
          operation.discard(CancellationException("The offline operation was cancelled"))
        },
        // Teardown already closed every outstanding handle.
        reject = {},
      )
    if (!posted) {
      logger?.v { "Offline operation ${handle.id} was cancelled after its runtime closed" }
    }
  }

  private fun runLoop() {
    val runtime =
      try {
        MlnFfiRuntimeOwner.open(cacheFile, { logger }, "MapLibre offline runtime")
          .also { runtimeOwner = it }
          .runtime
      } catch (error: Throwable) {
        logger?.e(error) { "Could not create the MapLibre runtime for offline management" }
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
        // Owner-thread affine (validated natively), so this cannot be hoisted into start().
        runtime.acquireWakeSource()
      } catch (error: Throwable) {
        logger?.e(error) { "Could not acquire a wake source for the MapLibre offline runtime" }
        teardown(runtime)
        return
      }
    acceptLock.withLock { wake = source }

    try {
      while (!stopRequested) {
        // Queued work first: a task posted before the wake source was published set no flag, so
        // draining only after a pump returns would leave it parked behind.
        runTasks(runtime)
        if (stopRequested) break
        check(!acceptLock.isHeldByOwnerThread) { "the pump must not run under acceptLock" }
        // The runtime makes no progress on its own: no event is delivered and no download advances
        // except inside a pump.
        runtime.pump(PUMP_PARK_MILLIS)
        drainEvents(runtime)
      }
    } catch (error: Throwable) {
      logger?.e(error) { "The MapLibre offline runtime loop failed" }
    } finally {
      teardown(runtime)
    }
  }

  /** Drains events until the queue is momentarily empty. */
  private fun drainEvents(runtime: RuntimeHandle) {
    val events =
      try {
        runtime.drainEvents().events
      } catch (error: Throwable) {
        logger?.e(error) { "Failed to drain MapLibre offline runtime events" }
        return
      }
    for (event in events) {
      if (event.type == RuntimeEventType.OFFLINE_OPERATION_COMPLETED) {
        completeOperation(runtime, event)
      } else {
        runCatching { onEvent(event) }
          .onFailure { logger?.e(it) { "Failed to handle offline event ${event.type}" } }
      }
    }
  }

  private fun completeOperation(runtime: RuntimeHandle, event: RuntimeEvent) {
    val payload = event.payload as? RuntimeEventPayload.OfflineOperationCompleted
    if (payload == null) {
      logger?.w { "An offline operation completed without a payload naming it" }
      return
    }

    val operation = pending.remove(payload.operationId)
    if (operation == null) {
      // Expected after a cancellation: the caller discarded the operation before native finished.
      logger?.v { "Ignoring the completion of unknown offline operation ${payload.operationId}" }
      return
    }

    try {
      operation.complete(runtime, event)
    } catch (error: Throwable) {
      logger?.e(error) { "Failed to complete the offline operation to ${operation.description}" }
    } finally {
      closeQuietly(operation.handle, "the operation to ${operation.description}")
    }
  }

  private fun runTasks(runtime: RuntimeHandle) {
    while (true) {
      // Taken one at a time, and run outside the lock: a task posts, discards, and calls back.
      runTask(runtime, acceptLock.withLock { tasks.removeFirstOrNull() } ?: break)
    }
  }

  private fun runTask(runtime: RuntimeHandle, task: OwnerTask) {
    try {
      // Check at the execution boundary so a queued cancellation cannot start a destructive native
      // operation whose result nobody is waiting for.
      if (task.isCancelled()) {
        task.reject(CancellationException("The offline operation was cancelled before it started"))
        return
      }
      task.run(runtime)
    } catch (error: Throwable) {
      logger?.e(error) { "An offline runtime task failed" }
      // The task may have failed before reporting anything; a caller that already heard ignores
      // this.
      runCatching { task.reject(error) }
    }
  }

  private fun teardown(runtime: RuntimeHandle) {
    assertOwnerThread("teardown")
    val disposed =
      OfflineManagerException("The offline manager was disposed before the operation finished")

    rejectQueuedTasks(disposed)

    // Closing the runtime discards every queued event, so anything still waiting for its
    // OFFLINE_OPERATION_COMPLETED must be failed explicitly here or it waits forever.
    val outstanding = pending.values.toList()
    pending.clear()
    outstanding.forEach { operation ->
      // Handles close on the thread that owns them, which is this one.
      closeQuietly(operation.handle, "the operation to ${operation.description}")
      runCatching { operation.discard(disposed) }
        .onFailure {
          logger?.e(it) { "Failed to cancel the operation to ${operation.description}" }
        }
    }

    // Last, and only after its children: the provider retires before the runtime.
    runtimeOwner?.close()
    runtimeOwner = null
  }

  private fun rejectQueuedTasks(reason: Throwable) {
    // Stop accepting first so the drain cannot race a task that would then never run, and take the
    // wake source out in the same critical section so nothing can signal one that is closing.
    val abandoned = mutableListOf<OwnerTask>()
    val source = acceptLock.withLock {
      accepting = false
      abandoned.addAll(tasks)
      tasks.clear()
      wake.also { wake = null }
    }
    abandoned.forEach { runCatching { it.reject(reason) } }
    // A wake source is its own native handle: closing the runtime does not release it.
    source?.let { closing ->
      runCatching { closing.close() }
        .onFailure { logger?.w(it) { "Failed to close the offline runtime's wake source" } }
    }
  }

  private fun closeQuietly(handle: OfflineOperationHandle<*>, what: String) {
    runCatching { handle.close() }.onFailure { logger?.w(it) { "Failed to close $what" } }
  }

  private fun assertOwnerThread(operation: String) {
    check(thread.isCurrent()) {
      "$operation must run on the offline runtime's own thread ($OWNER_THREAD_NAME), but ran on " +
        "${currentMlnFfiThreadName()}. MapLibre enforces this natively, so the failure would " +
        "otherwise surface as a WrongThreadException at the FFI boundary."
    }
  }
}
