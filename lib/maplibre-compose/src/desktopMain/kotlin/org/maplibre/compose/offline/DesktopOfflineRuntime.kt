package org.maplibre.compose.offline

import co.touchlab.kermit.Logger
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.resource.DesktopResourceProvider
import org.maplibre.nativeffi.runtime.OfflineOperationHandle
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions

/** How long the pump parks while operations or downloads are in flight. */
private const val BUSY_PUMP_MILLIS = 8L

/** How long the pump parks once nothing has happened for a full iteration. */
private const val IDLE_PUMP_MILLIS = 100L

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
   * Guards [accepting] so that a task cannot be queued after the queue has been drained for the
   * last time.
   */
  private val acceptLock = ReentrantLock()
  private var accepting = true

  @Volatile private var stopRequested = false

  /** Owner-thread state. Never read or written from anywhere else. */
  private val pending = mutableMapOf<Long, PendingOperation>()

  private val thread =
    Thread(::runLoop, "maplibre-compose-offline").apply {
      // The pump must never keep a shutting-down application alive; disposal is what stops it.
      isDaemon = true
    }

  fun start() {
    thread.start()
  }

  /** Asks the owner thread to tear down. Returns immediately; nothing is awaited. */
  fun shutdown() {
    stopRequested = true
    // Queue a wake-up so teardown does not wait out the idle interval first.
    post(task = {}, reject = {})
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
        createRuntime()
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

    try {
      while (true) {
        // The runtime makes no progress on its own: no event is delivered and no download advances
        // except inside runOnce.
        runtime.runOnce()
        val events = drainEvents(runtime)
        val ran = runTasks(runtime)
        if (stopRequested) break
        // Park briefly instead of blocking on the queue: downloads enqueue their progress from a
        // database thread and nothing wakes this loop, so a blocking take would stall them
        // silently until some unrelated call happened to arrive.
        val idle = events == 0 && ran == 0 && pending.isEmpty()
        val timeout = if (idle) IDLE_PUMP_MILLIS else BUSY_PUMP_MILLIS
        tasks.poll(timeout, TimeUnit.MILLISECONDS)?.let { runTask(runtime, it) }
      }
    } catch (error: Throwable) {
      logger.e(error) { "The MapLibre offline runtime loop failed" }
    } finally {
      teardown(runtime)
    }
  }

  private fun createRuntime(): RuntimeHandle {
    // Created eagerly: MapLibre opens the database when the runtime is created and fails if the
    // directory is missing, which on a fresh machine it always is.
    runCatching { options.cachePath.parent?.let(Files::createDirectories) }
      .onFailure { logger.w(it) { "Could not create the MapLibre cache directory" } }

    return RuntimeHandle.create(
        RuntimeOptions().also {
          it.cachePath = options.cachePath.toString()
          it.maximumCacheSize = options.maximumCacheSizeBytes
        }
      )
      .also {
        // Downloads fetch through the same resource stack a map does, so a pack whose style lives
        // in the application's resources only resolves if the provider is installed here too.
        it.setResourceProvider(DesktopResourceProvider(logger))
        logger.i { "Created the MapLibre offline runtime on ${Thread.currentThread().name}" }
      }
  }

  /** Drains events until the queue is momentarily empty, returning how many were handled. */
  private fun drainEvents(runtime: RuntimeHandle): Int {
    var handled = 0
    while (true) {
      val event =
        try {
          runtime.pollEvent() ?: break
        } catch (error: Throwable) {
          logger.e(error) { "Failed to poll a MapLibre offline runtime event" }
          break
        }
      handled++
      if (event.type == RuntimeEventType.OFFLINE_OPERATION_COMPLETED) {
        completeOperation(runtime, event)
      } else {
        runCatching { onEvent(event) }
          .onFailure { logger.e(it) { "Failed to handle offline event ${event.type}" } }
      }
    }
    return handled
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

  /** Runs every queued task, returning how many there were. */
  private fun runTasks(runtime: RuntimeHandle): Int {
    var ran = 0
    while (true) {
      val task = tasks.poll() ?: break
      ran++
      runTask(runtime, task)
    }
    return ran
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

    // Last, and only after its children: a runtime whose close fails stays live and leaves its
    // thread unable to host another one, which is why this thread ends here rather than being
    // reused.
    runCatching { runtime.close() }
      .onFailure { logger.e(it) { "Failed to close the MapLibre offline runtime" } }
  }

  private fun rejectQueuedTasks(reason: Throwable) {
    // Stop accepting first, so the drain below cannot race a task that would then never run.
    acceptLock.withLock { accepting = false }
    val abandoned = mutableListOf<OwnerTask>()
    tasks.drainTo(abandoned)
    abandoned.forEach { runCatching { it.reject(reason) } }
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
