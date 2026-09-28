package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiOwnerLock
import org.maplibre.compose.mlnffi.MlnFfiOwnerThread
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MlnFfiResourceProvider
import org.maplibre.compose.resource.MlnFfiResourceProviderFactory
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapMode
import org.maplibre.nativeffi.map.MapOptions
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventMask
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.WakeSource

/** Parks in the native pump until a wake arrives, rather than on a bound. */
private const val PUMP_PARK_MILLIS = -1L

/**
 * Caps one native drain below a 120 Hz frame so posted gesture work runs before the next vsync. The
 * first queued task always runs; leftover work re-arms the wake flag.
 */
private const val PUMP_BUDGET_MILLIS = 4L

/**
 * The thread that owns one map's MapLibre runtime and map handle, and the only place calls on
 * either happen. A runtime belongs to the thread that created it, and there may be only one per
 * thread.
 *
 * Camera transitions only step while frames are being drawn: mbgl advances them from
 * `onDidFinishRenderingFrame`.
 *
 * The render session belongs to whichever thread attached it, and native refuses to destroy a map
 * that still has one attached, so teardown waits for that thread to close it.
 *
 * This loop uses a dedicated [MlnFfiOwnerThread] rather than a dispatcher or a pooled executor.
 * maplibre-native-ffi#631 proposes an owner thread inside the C API, which would retire this class.
 */
internal class MlnFfiMapRuntimeLoop(
  /** The extent the map is created with. Its scale factor is fixed for the map's lifetime. */
  private val extent: MapExtent,
  private val cacheFile: Path,
  private val getLogger: () -> MapLog?,
  private val resourceProviderFactory: MlnFfiResourceProviderFactory = ::MlnFfiResourceProvider,
  private val resourceConfig: MapResourceConfig = MapResourceConfig(),
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

  /** Work for the owner thread; [OwnerTask.abandon] runs instead if it never gets to run. */
  private class OwnerTask(
    val run: (MapHandle) -> Unit,
    val abandon: () -> Unit,
    val drainAfter: Boolean,
  )

  private class DrainBarrier(val run: () -> Unit, val abandon: () -> Unit)

  /**
   * The owner thread. A parked pump ignores interruption, so [close] is the only way to stop it.
   */
  private val completion = CompletableDeferred<Result<Unit>>()
  private val thread =
    MlnFfiOwnerThread("maplibre-compose-map") {
      completion.complete(runCatching { runLoop() })
    }

  /**
   * Guards [tasks], [accepting], and [wake] together: nothing may be queued after the final drain,
   * and nothing may signal a wake source that is closing.
   */
  private val acceptLock = MlnFfiOwnerLock(thread)
  private val tasks = ArrayDeque<OwnerTask>()
  /** Callbacks that run after the next native pump and event drain. Owner thread only. */
  private val eventDrainBarriers = mutableListOf<DrainBarrier>()
  private var accepting = true
  private var wake: WakeSource? = null

  @Volatile private var stopRequested = false

  /** Owner-thread state: the runtime and everything retired before it. */
  private var runtimeOwner: MlnFfiRuntimeOwner? = null

  private val stopSignal = MlnFfiGate()

  /** The map, once it exists. Null before creation and after teardown begins. */
  @Volatile
  var map: MapHandle? = null
    private set

  /** The first failure that stopped this loop. */
  @Volatile
  var failure: Throwable? = null
    private set

  /** Release failures, recorded only by the owner and reported after every release is attempted. */
  private val cleanupFailures = mutableListOf<Throwable>()

  /** The density this loop's map was created with; a change means a new loop, not a resize. */
  val scaleFactor: Double
    get() = extent.scaleFactor

  fun start(startThread: (MlnFfiOwnerThread) -> Unit = MlnFfiOwnerThread::start) {
    try {
      startThread(thread)
    } catch (error: Throwable) {
      // No owner body will run to abandon queued work or acknowledge destruction. There are no
      // native resources to release; the caller receives the startup failure directly.
      failure = error
      rejectQueuedTasks()
      completion.complete(Result.success(Unit))
      throw error
    }
  }

  /** Whether the calling thread is the one that owns this loop's runtime and map. */
  fun isOwnerThread(): Boolean = thread.isCurrent()

  /**
   * Runs [action] on the owner thread and waits until it has run or been dropped. Returns null when
   * there is no map, or when the loop stopped before the work could run. Runs inline when the
   * caller is already the owner thread. A queued call ends its task batch so native events are
   * processed before later queued work.
   *
   * [abandon] runs when [action] will not run: the loop has already stopped, or a queued task is
   * dropped. An interrupt on the waiting thread does not drop the work. The wait continues, and the
   * interrupt status is restored when this returns.
   */
  fun <T> call(action: (MapHandle) -> T, abandon: () -> Unit = {}): T? {
    if (thread.isCurrent()) {
      val current = map
      if (current == null) {
        abandon()
        return null
      }
      return action(current)
    }
    if (map == null) {
      abandon()
      return null
    }

    var result: Result<T>? = null
    val done = MlnFfiGate()
    val posted =
      submit(
        run = { map ->
          result = runCatching { action(map) }
          done.open()
        },
        abandon = {
          try {
            abandon()
          } finally {
            done.open()
          }
        },
        drainAfter = true,
      )
    if (!posted) {
      abandon()
      return null
    }

    done.awaitUntilOpen()
    return result?.getOrThrow()
  }

  /** Suspends instead of blocking the caller; cancellation drops work that has not started. */
  suspend fun <T> await(action: (MapHandle) -> T): T? =
    suspendCancellableCoroutine { continuation ->
      val accepted =
        postAndDrainEvents(
          action = { map ->
            if (continuation.isActive) continuation.resumeWith(runCatching { action(map) })
          },
          abandon = { continuation.resume(null) },
        )
      if (!accepted) continuation.resume(null)
    }

  /** Queues [action] for the owner thread, reporting whether it was accepted. */
  fun post(action: (MapHandle) -> Unit, abandon: () -> Unit = {}): Boolean =
    submit(run = action, abandon = abandon)

  /** Keeps nested writes inside an owner commit; other callers enqueue their work. */
  fun dispatch(action: (MapHandle) -> Unit, abandon: () -> Unit = {}): Boolean {
    if (!thread.isCurrent()) return post(action, abandon)
    val current = map ?: return false
    action(current)
    return true
  }

  /** Drains this action's events before executing later queued work. */
  fun postAndDrainEvents(action: (MapHandle) -> Unit, abandon: () -> Unit = {}): Boolean =
    submit(run = action, abandon = abandon, drainAfter = true)

  /** Queues a callback that runs after the next native pump and event drain. */
  fun postEventDrainBarrier(action: () -> Unit, abandon: () -> Unit = {}): Boolean =
    post(action = { eventDrainBarriers += DrainBarrier(action, abandon) }, abandon = abandon)

  private fun submit(
    run: (MapHandle) -> Unit,
    abandon: () -> Unit,
    drainAfter: Boolean = false,
  ): Boolean = acceptLock.withLock {
    if (!accepting) return false
    tasks.add(OwnerTask(run, abandon, drainAfter))
    // Signalled under the lock so it cannot race the source's close, which would throw.
    wake?.signal()
    true
  }

  /**
   * Rejects new work and requests destruction. The caller must first release every render session
   * not owned by [onMapClosing]. [awaitClosed] acknowledges actual map and runtime destruction.
   */
  override fun close() {
    stopRequested = true
    stopSignal.open()
    acceptLock.withLock {
      accepting = false
      wake?.signal()
    }
  }

  suspend fun awaitClosed() = completion.await().getOrThrow()

  private fun runLoop() {
    val owner =
      try {
        MlnFfiRuntimeOwner.open(
            cacheFile,
            getLogger,
            "MapLibre runtime",
            resourceProviderFactory,
            resourceConfig,
          )
          .also { runtimeOwner = it }
      } catch (error: Throwable) {
        logger?.e(error) { "Could not create the MapLibre runtime" }
        fail(error)
        return
      }
    val runtime = owner.runtime

    var created: MapHandle? = null
    try {
      created = MapHandle.create(runtime, mapOptions())
      onMapCreated(created)
      map = created
      onMapPublished(created)
      // The renderer cannot attach until a map exists, and nothing else will tell it one now does.
      requestFrame()
      pump(runtime, created)
    } catch (error: Throwable) {
      logger?.e(error) { "The MapLibre map runtime loop failed" }
      fail(error)
    } finally {
      // A failed loop still owns its map until the renderer acknowledges release via close().
      stopSignal.awaitUntilOpen()
      rejectQueuedTasks()
      runCatching { created?.let(onMapClosing) }
        .exceptionOrNull()
        ?.let(cleanupFailures::addCleanupFailure)
      map = null
      runCatching { created?.close() }.exceptionOrNull()?.let(cleanupFailures::addCleanupFailure)
      runCatching { owner.close() }.exceptionOrNull()?.let(cleanupFailures::addCleanupFailure)
      runtimeOwner = null
      cleanupFailures.cleanupResult("Native map").getOrThrow()
    }
  }

  private fun pump(runtime: RuntimeHandle, map: MapHandle) {
    val source = runtime.acquireWakeSource()
    acceptLock.withLock { wake = source }
    while (!stopRequested) {
      // Queued work first: a task posted before the source was published set no wake flag.
      val ranTasks = runTasks(map)
      if (stopRequested) {
        abandonDrainBarriers()
        break
      }
      check(!acceptLock.isHeldByOwnerThread) { "the pump must not run under acceptLock" }
      // A batch that ran must not park: a task queuing nothing for native has nothing to wake it.
      runtime.pump(if (ranTasks) 0L else PUMP_PARK_MILLIS, PUMP_BUDGET_MILLIS)
      drainEvents(runtime, map)
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

  private fun drainEvents(runtime: RuntimeHandle, map: MapHandle) {
    // A failed drain must stop the loop: continuing could hand an undelivered style event to the
    // producer installed by onEventsDrained. The outer failure path owns shutdown and cleanup.
    val events = runtime.drainEvents().events
    for (event in events) {
      if (event.mapSource != null && event.mapSource !== map) continue
      runCatching { onEvent(map, event) }
        .onFailure { logger?.e(it) { "Failed to handle MapLibre event ${event.type}" } }
    }
    runCatching { onEventsDrained(map) }
      .onFailure { logger?.e(it) { "Failed to finish handling a MapLibre event batch" } }
    val barriers = eventDrainBarriers.toList()
    eventDrainBarriers.clear()
    barriers.forEach { runCatching { it.run() } }
  }

  /** Runs everything queued, reporting whether anything ran. */
  private fun runTasks(map: MapHandle): Boolean {
    var ran = false
    while (true) {
      // Taken one at a time, and run outside the lock: a task posts, closes, and calls back.
      val task = acceptLock.withLock { tasks.removeFirstOrNull() } ?: break
      ran = true
      try {
        task.run(map)
      } catch (error: Throwable) {
        logger?.e(error) { "A map owner-thread task failed" }
      }
      if (task.drainAfter) break
    }
    return ran
  }

  private fun fail(error: Throwable) {
    val firstFailure = failure == null
    failure = failure ?: error
    if (firstFailure) runCatching { onFailure(error) }
    rejectQueuedTasks()
    // The renderer republishes the failure, but only from a frame.
    runCatching { requestFrame() }
  }

  private fun rejectQueuedTasks() {
    // Stop accepting, drain the queue, and take the wake source out under one lock, so the drain
    // cannot race a task that would then never run and nothing can signal a source that is about to
    // close.
    val abandoned = mutableListOf<OwnerTask>()
    val source = acceptLock.withLock {
      accepting = false
      abandoned.addAll(tasks)
      tasks.clear()
      wake.also { wake = null }
    }
    // Released rather than run: a caller blocked in call() would otherwise never be resumed.
    abandoned.forEach { runCatching { it.abandon() } }
    abandonDrainBarriers()
    // A wake source is its own native handle, so closing the runtime does not release it.
    source?.let { closing ->
      runCatching { closing.close() }.onFailure { cleanupFailures.addCleanupFailure(it) }
    }
  }

  private fun abandonDrainBarriers() {
    val barriers = eventDrainBarriers.toList()
    eventDrainBarriers.clear()
    barriers.forEach { runCatching { it.abandon() } }
  }
}
