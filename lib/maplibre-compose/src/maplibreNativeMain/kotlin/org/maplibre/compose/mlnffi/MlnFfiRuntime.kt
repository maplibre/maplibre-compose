@file:OptIn(
  org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class,
  kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
import org.maplibre.nativeffi.generated.RuntimeEventHandler
import org.maplibre.nativeffi.generated.RuntimeEventSourceType
import org.maplibre.nativeffi.generated.RuntimeHandle

/** One native runtime whose events and map callbacks run on main. */
internal class MlnFfiRuntime(
  val options: MlnFfiRuntimeOptions,
  private val resourceConfig: MapResourceConfig =
    MapResourceConfig(options.requestInterceptor, options.resourceProvider, options.logger),
) {
  /** A map's event handler, running synchronously on main. */
  interface MapChild {
    fun onEvent(event: RuntimeEvent)
  }

  val mainDispatcher = options.mainDispatcher ?: Dispatchers.Main
  private val scope =
    CoroutineScope(SupervisorJob() + mainDispatcher + CoroutineName("maplibre-compose-runtime"))

  // Not cancelled at close: awaitClosed waits for it before releasing the runtime.
  private val acceptedWork = SupervisorJob()

  /**
   * Runs work that native has accepted, such as offline pack bookkeeping, which must finish before
   * the runtime is released. [awaitClosed] waits for it.
   */
  val acceptedWorkScope: CoroutineScope = CoroutineScope(acceptedWork + mainDispatcher)
  private val lock = MlnFfiLock()
  private val maps = mutableMapOf<ULong, MapChild>()
  private var closed = false
  private val opened = CompletableDeferred<MlnFfiRuntimeOwner>()
  private var opening: Job? = null

  var onFailure: (Throwable) -> Unit = {}

  /** Receives runtime events that no map raised, on main. */
  var onRuntimeEvent: (RuntimeEvent) -> Unit = {}

  /** Posts native callbacks and graphics notifications without running them inline. */
  fun postToMain(action: () -> Unit) {
    mainDispatcher.dispatch(EmptyCoroutineContext, Runnable { if (!closed) action() })
  }

  /**
   * Posts [action] to main even after close. It may only complete Kotlin state, such as a waiter
   * that must not be left suspended.
   */
  fun completeOnMain(action: () -> Unit) {
    mainDispatcher.dispatch(EmptyCoroutineContext, Runnable(action))
  }

  /**
   * [completion]'s outcome, completed on main after the events native raised before it and before
   * later ones. Its completion handlers run on main at that point. [completion] itself completes on
   * a native thread, so main can see it before those events.
   */
  fun <T> inEventOrder(completion: Deferred<T>): Deferred<T> {
    val ordered = CompletableDeferred<T>()
    // The binding completes [completion] inside native's completion callback, which runs after the
    // event callbacks that posted the command's events, so this post follows theirs.
    completion.invokeOnCompletion { error ->
      completeOnMain {
        if (error != null) ordered.completeExceptionally(error)
        else ordered.complete(completion.getCompleted())
      }
    }
    return ordered
  }

  /** Starts suspending work from an event handler. */
  fun launchOnMain(action: suspend () -> Unit) = scope.launch { action() }

  @Volatile
  var failure: Throwable? = null
    private set

  /** Opens the native runtime off the calling thread and delivers its events on main. */
  fun start() {
    opening =
      scope.launch(Dispatchers.IO) {
        val owner =
          try {
            MlnFfiRuntimeOwner.open(
              options.cacheFile,
              { options.logger },
              "MapLibre runtime",
              options.resourceProviderFactory,
              resourceConfig,
              eventHandler =
                RuntimeEventHandler { batch ->
                  postToMain { batch.events.forEach(::dispatch) }
                },
            )
          } catch (error: Throwable) {
            withContext(mainDispatcher) {
              fail(error)
              opened.completeExceptionally(error)
            }
            return@launch
          }
        withContext(NonCancellable + mainDispatcher) {
          if (!opened.complete(owner)) {
            // Closed while opening: nothing else will release it.
            runCatching { owner.close() }
          }
        }
      }
  }

  /** The native runtime, once it has opened. */
  suspend fun awaitRuntime(): RuntimeHandle = opened.await().runtime

  /** Routes events from [map] to [child] until [unregister]. */
  fun register(map: MapHandle, child: MapChild): Boolean = lock.withLock {
    if (closed) return@withLock false
    maps[map.id] = child
    true
  }

  fun unregister(map: MapHandle) {
    val source = map.id
    lock.withLock { maps.remove(source) }
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

  /** Releases the runtime after its maps. Throws what cleanup reported. */
  suspend fun awaitClosed() =
    withContext(NonCancellable + mainDispatcher) {
      opening?.join()
      val owner =
        try {
          opened.await()
        } catch (_: Throwable) {
          null
        }
      acceptedWork.complete()
      acceptedWork.join()
      scope.cancel()
      val failures = mutableListOf<Throwable>()
      if (owner != null) runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
      failures.throwCleanupFailures()
    }
}
