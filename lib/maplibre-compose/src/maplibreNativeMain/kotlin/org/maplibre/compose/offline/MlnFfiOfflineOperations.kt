package org.maplibre.compose.offline

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.currentMlnFfiThreadName
import org.maplibre.compose.util.throwCleanupFailures
import org.maplibre.nativeffi.runtime.OfflineOperationHandle
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle

/** Offline operation handles and completions on the public runtime's owner queue. */
internal class MlnFfiOfflineOperations(
  private val owner: MlnFfiRuntime,
  private val logger: MapLog?,
  private val onEvent: (RuntimeEvent) -> Unit,
) {
  @Volatile private var open = true
  private var closed = false

  init {
    owner.onOfflineEvent = ::handleEvent
    owner.onOfflineClosing = { reason ->
      finishClose(reason)
      cleanupFailures.throwCleanupFailures()
    }
  }

  private class PendingOperation(
    val description: String,
    val handle: OfflineOperationHandle<*>,
    val complete: (RuntimeHandle, RuntimeEvent) -> Unit,
    val discard: (Throwable) -> Unit,
  )

  private val pending = mutableMapOf<Long, PendingOperation>()
  private val cleanupFailures = mutableListOf<Throwable>()

  fun shutdown() {
    open = false
    owner.post(
      MlnFfiRuntime.Task(
        run = { finishClose(OfflineStorageException("The offline storage is closed")) },
        reject = {},
      )
    )
    // A failed owner calls finishClose as part of its teardown.
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
  ): Boolean {
    if (!open) return false
    return owner.post(
      MlnFfiRuntime.Task(
        run = { runtime ->
          // Check at the execution boundary so a queued cancellation cannot start a destructive
          // native operation whose result nobody is waiting for.
          if (!open) {
            reject(OfflineStorageException("The offline storage is closed"))
          } else if (isCancelled()) {
            reject(CancellationException("The offline operation was cancelled before it started"))
          } else {
            task(runtime)
          }
        },
        reject = reject,
      )
    )
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
      logger?.d { "Offline operation ${handle.id} was cancelled after its runtime closed" }
    }
  }

  private fun finishClose(reason: Throwable) {
    if (closed) return
    closed = true
    open = false
    val outstanding = pending.values.toList()
    pending.clear()
    outstanding.forEach { operation ->
      closeQuietly(operation.handle, "the operation to ${operation.description}")
      runCatching { operation.discard(reason) }
        .onFailure {
          logger?.e(it) { "Failed to cancel the operation to ${operation.description}" }
        }
    }
  }

  private fun handleEvent(runtime: RuntimeHandle, event: RuntimeEvent) {
    if (!open) return
    if (event.type == RuntimeEventType.OFFLINE_OPERATION_COMPLETED) {
      completeOperation(runtime, event)
    } else {
      runCatching { onEvent(event) }
        .onFailure { logger?.e(it) { "Failed to handle offline event ${event.type}" } }
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
    check(owner.isCurrent()) {
      "$operation must run on the native runtime owner thread (maplibre-compose-runtime), but ran on " +
        "${currentMlnFfiThreadName()}. MapLibre enforces this natively, so the failure would " +
        "otherwise surface as a WrongThreadException at the FFI boundary."
    }
  }
}
