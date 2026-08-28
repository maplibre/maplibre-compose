package org.maplibre.compose.mlnffi

import co.touchlab.kermit.Logger
import kotlin.coroutines.cancellation.CancellationException
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.nativeffi.runtime.OfflineOperationHandle
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle

/**
 * Parks until a wake arrives, for the same reason the map loop does; see `MlnFfiMapRuntimeLoop`.
 */
private const val PUMP_PARK_MILLIS = -1L

/** Names the owner thread, and names it again in the message a wrong-thread call fails with. */
private const val OWNER_THREAD_NAME = "maplibre-compose-runtime"

/**
 * The application-scoped MapLibre runtime: one owner thread, one [RuntimeHandle] with its resource
 * provider, and the only place native calls on either happen.
 *
 * A runtime belongs to the thread that created it, there may be only one per thread, and that
 * thread may not be the AWT event thread or any other pooled thread that something else might also
 * put a runtime on. Hence a dedicated thread rather than a borrowed one: this runtime outlives any
 * particular map, so a map's owner thread cannot host it. Each map still runs its own runtime on
 * its own thread; two runtimes sharing one cache database is safe, measured by
 * `MlnFfiSharedCacheDatabaseTest`.
 */
internal class MlnFfiRuntime(
  private val options: MlnFfiRuntimeOptions,
  private val logger: Logger,
) : MlnFfiOwnerLoop<MlnFfiRuntime.OwnerTask>(OWNER_THREAD_NAME) {

  override val loopLogger: Logger
    get() = logger

  /** Work for the owner thread, with the failure path that runs when the work never does. */
  class OwnerTask(
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
   * Consumers of every runtime event that is not an operation completion. Guarded by acceptLock.
   */
  private val listeners = mutableListOf<(RuntimeEvent) -> Unit>()

  /** Owner-thread state. Never read or written from anywhere else. */
  private val pending = mutableMapOf<Long, PendingOperation>()

  /** The runtime and everything retired before it. Owner-thread state; see [MlnFfiRuntimeOwner]. */
  private var runtimeOwner: MlnFfiRuntimeOwner? = null

  /** Subscribes [listener] to every event this runtime raises, on the owner thread. */
  fun addEventListener(listener: (RuntimeEvent) -> Unit) {
    acceptLock.withLock { listeners.add(listener) }
  }

  /** Asks the owner thread to tear down. Returns immediately; nothing is awaited. */
  fun shutdown() {
    requestStop()
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
  ): Boolean = submit(OwnerTask(task, reject, isCancelled))

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
          closeQuietly(operation.handle, "a cancelled runtime operation")
          operation.discard(CancellationException("The runtime operation was cancelled"))
        },
        // Teardown already closed every outstanding handle.
        reject = {},
      )
    if (!posted) {
      logger.v { "Runtime operation ${handle.id} was cancelled after its runtime closed" }
    }
  }

  override fun runLoop() {
    val runtime =
      try {
        MlnFfiRuntimeOwner.open(
            options.cacheFile,
            { logger },
            "MapLibre application runtime",
            options.resourceProviderFactory,
          )
          .also { runtimeOwner = it }
          .runtime
      } catch (error: Throwable) {
        logger.e(error) { "Could not create the MapLibre application runtime" }
        rejectQueuedTasks(
          IllegalStateException(
            "The MapLibre application runtime could not be created: " +
              (error.message ?: error::class.simpleName)
          )
        )
        return
      }

    try {
      // Owner-thread affine (validated natively), so this cannot be hoisted into start().
      publishWakeSource(runtime.acquireWakeSource())
    } catch (error: Throwable) {
      logger.e(error) { "Could not acquire a wake source for the MapLibre application runtime" }
      teardown(runtime)
      return
    }

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
      logger.e(error) { "The MapLibre application runtime loop failed" }
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
        logger.e(error) { "Failed to drain MapLibre application runtime events" }
        return
      }
    if (events.isEmpty()) return
    val handlers = acceptLock.withLock { listeners.toList() }
    for (event in events) {
      if (event.type == RuntimeEventType.OFFLINE_OPERATION_COMPLETED) {
        completeOperation(runtime, event)
      } else {
        handlers.forEach { handler ->
          runCatching { handler(event) }
            .onFailure { logger.e(it) { "Failed to handle runtime event ${event.type}" } }
        }
      }
    }
  }

  private fun completeOperation(runtime: RuntimeHandle, event: RuntimeEvent) {
    val payload = event.payload as? RuntimeEventPayload.OfflineOperationCompleted
    if (payload == null) {
      logger.w { "A runtime operation completed without a payload naming it" }
      return
    }

    val operation = pending.remove(payload.operationId)
    if (operation == null) {
      // Expected after a cancellation: the caller discarded the operation before native finished.
      logger.v { "Ignoring the completion of unknown runtime operation ${payload.operationId}" }
      return
    }

    try {
      operation.complete(runtime, event)
    } catch (error: Throwable) {
      logger.e(error) { "Failed to complete the operation to ${operation.description}" }
    } finally {
      closeQuietly(operation.handle, "the operation to ${operation.description}")
    }
  }

  private fun runTasks(runtime: RuntimeHandle) {
    while (true) {
      // Taken one at a time, and run outside the lock: a task posts, discards, and calls back.
      runTask(runtime, takeQueuedTask() ?: break)
    }
  }

  private fun runTask(runtime: RuntimeHandle, task: OwnerTask) {
    try {
      // Check at the execution boundary so a queued cancellation cannot start a destructive native
      // operation whose result nobody is waiting for.
      if (task.isCancelled()) {
        task.reject(CancellationException("The runtime operation was cancelled before it started"))
        return
      }
      task.run(runtime)
    } catch (error: Throwable) {
      logger.e(error) { "A runtime task failed" }
      // The task may have failed before reporting anything; a caller that already heard ignores
      // this.
      runCatching { task.reject(error) }
    }
  }

  private fun teardown(runtime: RuntimeHandle) {
    assertOwnerThread("teardown")
    val disposed =
      IllegalStateException("The MapLibre runtime was shut down before the operation finished")

    rejectQueuedTasks(disposed)

    // Closing the runtime discards every queued event, so anything still waiting for its
    // OFFLINE_OPERATION_COMPLETED must be failed explicitly here or it waits forever.
    val outstanding = pending.values.toList()
    pending.clear()
    outstanding.forEach { operation ->
      // Handles close on the thread that owns them, which is this one.
      closeQuietly(operation.handle, "the operation to ${operation.description}")
      runCatching { operation.discard(disposed) }
        .onFailure { logger.e(it) { "Failed to cancel the operation to ${operation.description}" } }
    }

    // Last, and only after its children: the provider retires before the runtime.
    runtimeOwner?.close()
    runtimeOwner = null
  }

  private fun rejectQueuedTasks(reason: Throwable) {
    drainQueuedTasks { it.reject(reason) }
  }

  private fun closeQuietly(handle: OfflineOperationHandle<*>, what: String) {
    runCatching { handle.close() }.onFailure { logger.w(it) { "Failed to close $what" } }
  }

  private fun assertOwnerThread(operation: String) {
    check(isOwnerThread()) {
      "$operation must run on the application runtime's own thread ($OWNER_THREAD_NAME), but ran " +
        "on ${currentMlnFfiThreadName()}. MapLibre enforces this natively, so the failure would " +
        "otherwise surface as a WrongThreadException at the FFI boundary."
    }
  }
}
