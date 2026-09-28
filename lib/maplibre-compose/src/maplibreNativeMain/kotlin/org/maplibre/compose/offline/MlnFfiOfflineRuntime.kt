package org.maplibre.compose.offline

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.files.Path
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MlnFfiRuntimeThread
import org.maplibre.compose.mlnffi.currentMlnFfiThreadName
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.compose.util.throwCleanupFailures
import org.maplibre.nativeffi.runtime.OfflineOperationHandle
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle

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
  private val logger: MapLog?,
  private val onEvent: (RuntimeEvent) -> Unit,
  private val resourceConfig: MapResourceConfig = MapResourceConfig(),
) {

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
  private val thread =
    MlnFfiRuntimeThread(
      name = OWNER_THREAD_NAME,
      getLogger = { logger },
      openRuntime = {
        MlnFfiRuntimeOwner.open(
          cacheFile,
          { logger },
          "MapLibre offline runtime",
          resourceConfig = resourceConfig,
        )
      },
      // The runtime makes no progress on its own: no event is delivered and no download advances
      // except inside a pump, so each pump drains everything that is ready.
      pumpBudgetMillis = -1L,
      host = OwnerHost(),
    )

  /** Owner-thread state. Never read or written from anywhere else. */
  private val pending = mutableMapOf<Long, PendingOperation>()
  private val cleanupFailures = mutableListOf<Throwable>()

  fun start() {
    thread.start()
  }

  /** Completes only after the owner has released every native resource. */
  suspend fun awaitClosed() = thread.awaitStopped()

  /** Asks the owner thread to tear down. Returns immediately; nothing is awaited. */
  fun shutdown() {
    thread.stop()
  }

  /**
   * Queues [task] for the owner thread.
   *
   * Returns false when the runtime is already gone, in which case [reject] is not called and the
   * caller reports the failure itself. Otherwise exactly one of [task] and [reject] runs, unless
   * [isCancelled] is true when the owner thread reaches it, in which case [reject] receives a
   * cancellation so resources reserved before posting can be released. A [task] that throws also
   * passes its error to [reject].
   */
  fun post(
    task: (RuntimeHandle) -> Unit,
    reject: (Throwable) -> Unit,
    isCancelled: () -> Boolean = { false },
  ): Boolean =
    thread.post(
      MlnFfiRuntimeThread.Task(
        run = { runtime ->
          // Check at the execution boundary so a queued cancellation cannot start a destructive
          // native operation whose result nobody is waiting for.
          if (isCancelled()) {
            reject(CancellationException("The offline operation was cancelled before it started"))
          } else {
            task(runtime)
          }
        },
        reject = reject,
      )
    )

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
      logger?.d { "Offline operation ${handle.id} was cancelled after its runtime closed" }
    }
  }

  private inner class OwnerHost : MlnFfiRuntimeThread.Host {
    override fun afterPump(runtime: RuntimeHandle) {
      drainEvents(runtime)
    }

    override fun onLoopFailure(error: Throwable, runtime: RuntimeHandle?): Throwable {
      if (runtime == null) {
        logger?.e(error) { "Could not create the MapLibre runtime for offline management" }
        return OfflineManagerException(
          "The MapLibre offline runtime could not be created: " +
            (error.message ?: error::class.simpleName),
          error,
        )
      }
      logger?.e(error) { "The MapLibre offline runtime loop failed" }
      return stopReason()
    }

    override fun stopReason(): Throwable =
      OfflineManagerException("The offline manager was disposed before the operation finished")

    override fun onStopping(runtime: RuntimeHandle?, failures: MutableList<Throwable>) {
      // Closing the runtime discards every queued event, so anything still waiting for its
      // OFFLINE_OPERATION_COMPLETED must be failed explicitly here or it waits forever.
      val disposed = stopReason()
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
    }

    override fun reportCleanup(failures: List<Throwable>) {
      (cleanupFailures + failures).throwCleanupFailures()
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
      logger?.d { "Ignoring the completion of unknown offline operation ${payload.operationId}" }
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

  private fun closeQuietly(handle: OfflineOperationHandle<*>, what: String) {
    runCatching { handle.close() }
      .onFailure {
        cleanupFailures.add(it)
        logger?.w(it) { "Failed to close $what" }
      }
  }

  private fun assertOwnerThread(operation: String) {
    check(thread.isCurrent()) {
      "$operation must run on the offline runtime's own thread ($OWNER_THREAD_NAME), but ran on " +
        "${currentMlnFfiThreadName()}. MapLibre enforces this natively, so the failure would " +
        "otherwise surface as a WrongThreadException at the FFI boundary."
    }
  }
}
