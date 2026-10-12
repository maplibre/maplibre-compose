@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.compose.util.throwCleanupFailures
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.RuntimeEvent
import org.maplibre.nativeffi.generated.RuntimeEventSourceType
import org.maplibre.nativeffi.generated.RuntimeHandle

/** One native runtime whose events and map callbacks are drained synchronously on main. */
internal class MlnFfiRuntime(
  val options: MlnFfiRuntimeOptions,
  private val resourceConfig: MapResourceConfig =
    MapResourceConfig(options.requestInterceptor, options.resourceProvider, options.logger),
) {
  /** A map's event handler, running on main without suspending a drain. */
  interface MapChild {
    fun onEvent(event: RuntimeEvent)
  }

  val mainDispatcher = options.mainDispatcher ?: Dispatchers.Main
  internal val mainScope =
    CoroutineScope(SupervisorJob() + mainDispatcher + CoroutineName("maplibre-compose-runtime"))
  private val lock = MlnFfiLock()
  private val maps = mutableMapOf<ULong, MapChild>()
  private var closed = false
  private val opened = CompletableDeferred<MlnFfiRuntimeOwner>()
  private var opening: Job? = null
  private var runtime: RuntimeHandle? = null
  private var draining = false
  private var drainAgain = false

  var onFailure: (Throwable) -> Unit = {}

  /** Receives runtime events that no map raised, on main. */
  var onRuntimeEvent: (RuntimeEvent) -> Unit = {}

  /** Posts native callbacks and graphics notifications without running them inline. */
  fun postToMain(action: () -> Unit) {
    mainDispatcher.dispatch(EmptyCoroutineContext, Runnable { if (!closed) action() })
  }

  /** Async event handlers keep the synchronous drain free to deliver the rest of the batch. */
  fun launchOnMain(action: suspend () -> Unit) = mainScope.launch { action() }

  @Volatile
  var failure: Throwable? = null
    private set

  /** Opens the native runtime off the calling thread, then starts draining its events. */
  fun start() {
    opening =
      mainScope.launch(Dispatchers.IO) {
        val owner =
          try {
            MlnFfiRuntimeOwner.open(
              options.cacheFile,
              { options.logger },
              "MapLibre runtime",
              options.resourceProviderFactory,
              resourceConfig,
              eventWake = { postToMain { drain() } },
            )
          } catch (error: Throwable) {
            withContext(mainDispatcher) {
              fail(error)
              opened.completeExceptionally(error)
            }
            return@launch
          }
        withContext(NonCancellable + mainDispatcher) {
          runtime = owner.runtime
          if (!opened.complete(owner)) {
            // Closed while opening: nothing else will release it.
            runtime = null
            runCatching { owner.close() }
          } else {
            drain()
          }
        }
      }
  }

  /** The native runtime, once it has opened. */
  suspend fun awaitRuntime(): RuntimeHandle = opened.await().runtime

  /**
   * Routes the events [map] raises to [child] until [unregister]. Events it raised before this call
   * are dropped.
   */
  fun register(map: MapHandle, child: MapChild): Boolean = lock.withLock {
    if (closed) return@withLock false
    maps[map.id] = child
    true
  }

  fun unregister(map: MapHandle) {
    val source = map.id
    lock.withLock { maps.remove(source) }
  }

  /**
   * Delivers queued events inline on main. A nested call asks the outer drain to keep going. Native
   * queues a command's events before completing it, so awaiting it on main and then draining
   * delivers those events before the caller continues.
   */
  fun drain() {
    if (draining) {
      drainAgain = true
      return
    }
    val runtime = runtime ?: return
    draining = true
    try {
      do {
        drainAgain = false
        while (true) {
          val events =
            try {
              runtime.drainEvents()?.use { it.get().events } ?: break
            } catch (error: Throwable) {
              options.logger?.e(error) { "Could not drain MapLibre runtime events" }
              return
            }
          for (event in events) dispatch(event)
        }
      } while (drainAgain)
    } finally {
      draining = false
    }
  }

  private fun dispatch(event: RuntimeEvent) {
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
    options.logger?.e(error) { "The MapLibre runtime failed" }
    runCatching { onFailure(error) }
  }

  /** Refuses new maps. Returns at once; see [awaitClosed]. */
  fun close() {
    lock.withLock { closed = true }
    opened.cancel(CancellationException("The map runtime closed before it opened"))
  }

  /**
   * Stops draining events and releases the runtime after its maps. Throws what cleanup reported.
   */
  suspend fun awaitClosed() =
    withContext(NonCancellable + mainDispatcher) {
      opening?.join()
      val owner =
        try {
          opened.await()
        } catch (_: Throwable) {
          null
        }
      mainScope.cancel()
      runtime = null
      val failures = mutableListOf<Throwable>()
      if (owner != null) runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
      failures.throwCleanupFailures()
    }
}
