@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.compose.util.throwCleanupFailures
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.RuntimeEvent
import org.maplibre.nativeffi.generated.RuntimeEventSourceType
import org.maplibre.nativeffi.generated.RuntimeHandle

/**
 * One native runtime for a public MapRuntime, and the coroutine that drains its events.
 *
 * Commands go straight to native from any thread; the runtime's own thread runs them in submission
 * order. The runtime's event wake starts a drain on [eventPump], one coroutine that hands each
 * event to the map that raised it, in queue order, and then lets every map finish the drain.
 */
internal class MlnFfiRuntime(
  val options: MlnFfiRuntimeOptions,
  private val resourceConfig: MapResourceConfig =
    MapResourceConfig(options.requestInterceptor, options.resourceProvider, options.logger),
) {
  /** A map's event handlers. Both run on the event pump, one call at a time. */
  interface MapChild {
    suspend fun onEvent(event: RuntimeEvent)

    /**
     * Runs after each drain, and after each [requestDrain]. [drain] numbers the drain; it is
     * greater than [drainsStarted] read at any time before the drain started fetching events.
     */
    suspend fun onEventsDrained(drain: Long)
  }

  private val scope =
    CoroutineScope(
      SupervisorJob() + Dispatchers.Default + CoroutineName("maplibre-compose-runtime")
    )
  private val wakes = Channel<Unit>(Channel.CONFLATED)
  private val lock = MlnFfiLock()
  private val maps = mutableMapOf<ULong, MapChild>()
  private var closed = false
  private val opened = CompletableDeferred<MlnFfiRuntimeOwner>()
  private val ready = CompletableDeferred<Result<Unit>>()
  private var eventPump: Job? = null

  var onFailure: (Throwable) -> Unit = {}

  /** The number of the latest drain that has started fetching events. */
  @Volatile
  var drainsStarted: Long = 0L
    private set

  /** Receives runtime events that no map raised, on the event pump. */
  var onRuntimeEvent: suspend (RuntimeEvent) -> Unit = {}

  @Volatile
  var failure: Throwable? = null
    private set

  /** Opens the native runtime off the calling thread, then starts draining its events. */
  fun start() {
    scope.launch(Dispatchers.IO) {
      val owner =
        try {
          MlnFfiRuntimeOwner.open(
            options.cacheFile,
            { options.logger },
            "MapLibre runtime",
            options.resourceProviderFactory,
            resourceConfig,
            eventWake = { wakes.trySend(Unit) },
          )
        } catch (error: Throwable) {
          fail(error)
          opened.completeExceptionally(error)
          return@launch
        }
      if (!opened.complete(owner)) {
        // Closed while opening: nothing else will release it.
        runCatching { owner.close() }
        return@launch
      }
      eventPump = scope.launch { for (wake in wakes) drain(owner.runtime) }
    }
  }

  /** Marks the runtime usable once the work it needs before its first map has finished. */
  fun initialized(result: Result<Unit>) {
    ready.complete(result)
  }

  /** Waits until maps can use the runtime. */
  suspend fun awaitReady() {
    ready.await().getOrThrow()
    failure?.let { throw it }
  }

  /** The native runtime, once it has opened. */
  suspend fun awaitRuntime(): RuntimeHandle = opened.await().runtime

  /**
   * Routes the events [map] raises to [child] until [unregister]. Events it raised before this call
   * are dropped.
   */
  fun register(map: MapHandle, child: MapChild): Boolean = lock.withLock {
    if (closed) return@withLock false
    maps[map.eventSource()] = child
    true
  }

  fun unregister(map: MapHandle) {
    val source = map.eventSource()
    lock.withLock { maps.remove(source) }
  }

  /** Has the event pump drain and then run every map's drain handler, even with nothing queued. */
  fun requestDrain() {
    wakes.trySend(Unit)
  }

  private suspend fun drain(runtime: RuntimeHandle) {
    val drain = ++drainsStarted
    while (true) {
      val events =
        try {
          runtime.drainEvents()?.use { it.get().events } ?: break
        } catch (error: Throwable) {
          if (error is CancellationException) throw error
          // Only a released runtime refuses a drain, and the pump stops before release.
          options.logger?.e(error) { "Could not drain MapLibre runtime events" }
          return
        }
      for (event in events) dispatch(event)
    }
    for (child in lock.withLock { maps.values.toList() }) {
      try {
        child.onEventsDrained(drain)
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        options.logger?.e(error) { "Failed to finish handling a MapLibre event batch" }
      }
    }
  }

  private suspend fun dispatch(event: RuntimeEvent) {
    try {
      if (event.sourceType == RuntimeEventSourceType.MAP) {
        lock.withLock { maps[event.source] }?.onEvent(event)
      } else {
        onRuntimeEvent(event)
      }
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      options.logger?.e(error) { "Failed to handle MapLibre event ${event.type}" }
    }
  }

  private fun fail(error: Throwable) {
    failure = error
    initialized(Result.failure(error))
    options.logger?.e(error) { "The MapLibre runtime failed" }
    runCatching { onFailure(error) }
  }

  /** Refuses new maps. Returns at once; see [awaitClosed]. */
  fun close() {
    lock.withLock { closed = true }
    initialized(Result.failure(IllegalStateException("The map runtime is closed")))
    opened.cancel(CancellationException("The map runtime closed before it opened"))
  }

  /**
   * Stops draining events and releases the runtime after its maps. Throws what cleanup reported.
   */
  suspend fun awaitClosed() =
    withContext(NonCancellable) {
      val owner =
        try {
          opened.await()
        } catch (_: Throwable) {
          null
        }
      wakes.close()
      eventPump?.join()
      val failures = mutableListOf<Throwable>()
      if (owner != null) runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
      scope.cancel()
      failures.throwCleanupFailures()
    }
}
