package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapMode
import org.maplibre.nativeffi.map.MapOptions
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventMask

/**
 * One map on the public runtime's owner queue. Commands submitted before creation wait locally;
 * after creation they join the shared FIFO. Ordered commands end a batch so their native events are
 * handled before later commands. Renderer sessions stay on the thread that attached them.
 */
internal class MlnFfiMapRuntimeLoop(
  /** The extent the map is created with. Its scale factor is fixed for the map's lifetime. */
  private val extent: MapExtent,
  private val owner: MlnFfiRuntime,
  private val getLogger: () -> MapLog?,
  /** Runs on the owner thread once the map exists, before it is published. */
  private val onMapCreated: (MapHandle) -> Unit,
  /** Runs on the owner thread after [map] publishes the created map. */
  private val onMapPublished: (MapHandle) -> Unit = {},
  /** Runs on the owner thread before the map is unpublished and destroyed. */
  private val onMapClosing: (MapHandle) -> Unit = {},
  /** Runs on the owner thread for every event this loop's runtime raises. */
  private val onEvent: (MapHandle, RuntimeEvent) -> Unit,
  /** Runs on the owner thread once the event queue is momentarily empty. */
  private val onEventsDrained: (MapHandle) -> Unit,
  /** Asks the host for a frame. Called from the owner thread. */
  private val requestFrame: () -> Unit,
  private val mapEventMask: RuntimeEventMask? = null,
  private val mapMode: MapMode = MapMode.CONTINUOUS,
  private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {

  private val logger: MapLog?
    get() = getLogger()

  private class DrainBarrier(val run: () -> Unit, val onDropped: () -> Unit)

  private val completion = CompletableDeferred<Result<Unit>>()
  private val acceptLock = MlnFfiLock()
  private val beforeStart = ArrayDeque<MlnFfiRuntime.Task>()
  private var accepting = true
  @Volatile
  var isStarted: Boolean = false
    private set

  /** Callbacks that run after the next native pump and event drain. Owner thread only. */
  private val eventDrainBarriers = mutableListOf<DrainBarrier>()

  /** The created map, published or not. Owner thread only. */
  private var created: MapHandle? = null

  private val stopSignal = MlnFfiGate()

  /** The map, once it exists. Null before creation and after teardown begins. */
  @Volatile
  var map: MapHandle? = null
    private set

  @Volatile private var creationFailure: Throwable? = null

  /** A creation failure or the failure of the shared native runtime. */
  val failure: Throwable?
    get() = creationFailure ?: owner.failure

  /** The density this loop's map was created with; a change means a new loop, not a resize. */
  val scaleFactor: Double
    get() = extent.scaleFactor

  /** Queues creation before the commands accepted while the child was unstarted. */
  fun start() {
    val refused = mutableListOf<MlnFfiRuntime.Task>()
    val accepted = acceptLock.withLock {
      check(!isStarted && accepting) { "The map was already started or closed" }
      isStarted = true
      val accepted =
        owner.post(
          MlnFfiRuntime.Task(
            run = { runtime ->
              val current = MapHandle.create(runtime, mapOptions())
              created = current
              owner.register(current, Child())
              onMapCreated(current)
              map = current
              onMapPublished(current)
              requestFrame()
            },
            reject = { error ->
              fail(error)
              if (created == null) completion.complete(Result.success(Unit))
            },
          )
        )
      while (beforeStart.isNotEmpty()) {
        val task = beforeStart.removeFirst()
        if (!owner.post(task)) refused += task
      }
      accepted
    }
    val reason = owner.failure ?: IllegalStateException("The map runtime is closed")
    if (!accepted) {
      fail(reason)
      completion.complete(Result.success(Unit))
    }
    refused.forEach { it.reject(reason) }
  }

  fun abandonQueuedTasks() {
    val abandoned = acceptLock.withLock {
      check(!isStarted)
      beforeStart.toList().also { beforeStart.clear() }
    }
    abandoned.forEach { it.reject(IllegalStateException("The map was not created")) }
  }

  fun isOwnerThread(): Boolean = owner.isCurrent()

  /**
   * Runs [action] on the owner thread and returns its result, or null when the loop stops first.
   * Rethrows what [action] throws. A caller cancelled before [action] starts skips it; with
   * [cancellable] false, [action] runs and the caller waits for it anyway. Use that for work that
   * must finish, such as work that uses native memory the caller frees once this returns.
   */
  suspend fun <T> await(cancellable: Boolean = true, action: (MapHandle) -> T): T? {
    if (owner.isCurrent()) {
      if (!acceptLock.withLock { accepting } || failure != null) return null
      val current = map ?: return null
      owner.endBatch()
      return action(current)
    }
    if (!cancellable) {
      return withContext(NonCancellable) {
        suspendCoroutine { continuation ->
          enqueue(
            run = { map -> continuation.resumeWith(runCatching { action(map) }) },
            onDropped = { continuation.resume(null) },
            ordered = true,
          )
        }
      }
    }
    return suspendCancellableCoroutine { continuation ->
      enqueue(
        run = { map ->
          if (continuation.isActive) continuation.resumeWith(runCatching { action(map) })
        },
        onDropped = { if (continuation.isActive) continuation.resume(null) },
        ordered = true,
      )
    }
  }

  /**
   * Runs [action] on the owner thread without waiting for it, in submission order. [onDropped] runs
   * if [action] never runs or throws. With [ordered], later work runs after this action's events.
   */
  fun submit(ordered: Boolean = false, onDropped: () -> Unit = {}, action: (MapHandle) -> Unit) {
    if (!owner.isCurrent()) return enqueue(action, onDropped, ordered)
    if (!acceptLock.withLock { accepting } || failure != null) return onDropped()
    val current = map ?: return onDropped()
    // Requested first, like [await], so an action that throws still ends the batch.
    if (ordered) owner.endBatch()
    try {
      action(current)
    } catch (error: Throwable) {
      onDropped()
      throw error
    }
  }

  /**
   * Suspends until the owner thread has pumped native work and handled the events raised so far,
   * then runs [action] there. Throws [IllegalStateException] when the loop stops first, and
   * rethrows what [action] throws.
   */
  suspend fun awaitEventsDrained(action: () -> Unit = {}) {
    val completion = CompletableDeferred<Result<Unit>>()
    val stopped: () -> Unit = {
      completion.complete(Result.failure(IllegalStateException("The map owner loop stopped")))
    }
    submit(ordered = true, onDropped = stopped) {
      eventDrainBarriers +=
        DrainBarrier(run = { completion.complete(runCatching(action)) }, stopped)
    }
    completion.await().getOrThrow()
  }

  /** The owner thread logs what [run] throws and then runs [onDropped]. */
  private fun enqueue(run: (MapHandle) -> Unit, onDropped: () -> Unit, ordered: Boolean) {
    val task =
      MlnFfiRuntime.Task(
        run = {
          val current = map
          if (acceptLock.withLock { accepting } && current != null && failure == null) run(current)
          else onDropped()
        },
        reject = { onDropped() },
        endsBatch = ordered,
      )
    val accepted = acceptLock.withLock {
      if (!accepting || failure != null) false
      else if (!isStarted) {
        beforeStart.add(task)
        true
      } else owner.post(task)
    }
    if (!accepted) onDropped()
  }

  /** Call after renderer release. Only this map is destroyed; the shared owner keeps running. */
  override fun close() {
    stopSignal.open()
    val abandoned = acceptLock.withLock {
      if (!accepting) return
      accepting = false
      val abandoned = beforeStart.toList()
      beforeStart.clear()
      if (!isStarted) completion.complete(Result.success(Unit))
      else owner.post(MlnFfiRuntime.Task(run = { destroy() }, reject = {}))
      // If the owner failed, its finalizer waits for stopSignal and performs destruction.
      abandoned
    }
    abandoned.forEach { it.reject(IllegalStateException("The map is closed")) }
  }

  suspend fun awaitClosed() = completion.await().getOrThrow()

  private fun fail(error: Throwable) {
    if (owner.failure == null) creationFailure = creationFailure ?: error
    runCatching { onFailure(error) }
    runCatching { requestFrame() }
  }

  private fun destroy() {
    if (completion.isCompleted) return
    abandonDrainBarriers()
    val failures = mutableListOf<Throwable>()
    created?.let { closing ->
      owner.unregister(closing)
      runCatching { onMapClosing(closing) }.exceptionOrNull()?.let(failures::add)
      map = null
      created = null
      runCatching { closing.close() }.exceptionOrNull()?.let(failures::add)
    }
    completion.complete(failures.cleanupResult("Native map"))
  }

  private inner class Child : MlnFfiRuntime.MapChild {
    override fun onEvent(event: RuntimeEvent) {
      val current = map ?: return
      runCatching { onEvent(current, event) }
        .onFailure { logger?.e(it) { "Failed to handle MapLibre event ${event.type}" } }
    }

    override fun onEventsDrained() {
      val current = map ?: return
      drainFinished(current)
    }

    override fun onFailure(error: Throwable) = fail(error)

    override fun onStopping() {
      abandonDrainBarriers()
      stopSignal.awaitUntilOpen()
      destroy()
    }
  }

  private fun mapOptions() =
    MapOptions().also {
      it.width = extent.width.coerceAtLeast(1)
      it.height = extent.height.coerceAtLeast(1)
      it.scaleFactor = extent.scaleFactor
      it.mapMode = mapMode
      mapEventMask?.let { mask -> it.eventMask = mask }
    }

  private fun drainFinished(map: MapHandle) {
    runCatching { onEventsDrained(map) }
      .onFailure { logger?.e(it) { "Failed to finish handling a MapLibre event batch" } }
    val barriers = eventDrainBarriers.toList()
    eventDrainBarriers.clear()
    barriers.forEach { barrier ->
      runCatching { barrier.run() }
        .onFailure { logger?.e(it) { "Failed to run work waiting for a MapLibre event drain" } }
    }
  }

  private fun abandonDrainBarriers() {
    val barriers = eventDrainBarriers.toList()
    eventDrainBarriers.clear()
    barriers.forEach { runCatching { it.onDropped() } }
  }
}
