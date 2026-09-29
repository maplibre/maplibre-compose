package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiOwnerThread
import org.maplibre.compose.mlnffi.MlnFfiRuntimeThread
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

/**
 * Caps one native drain below a 120 Hz frame so posted gesture work runs before the next vsync. The
 * first queued task always runs; leftover work re-arms the wake flag.
 */
private const val PUMP_BUDGET_MILLIS = 4L

/**
 * The thread that owns one map's MapLibre runtime and map handle. A runtime belongs to the thread
 * that created it, and there may be only one per thread.
 *
 * Camera transitions only step while frames are being drawn: mbgl advances them from
 * `onDidFinishRenderingFrame`.
 *
 * The render session belongs to whichever thread attached it, and native refuses to destroy a map
 * that still has one attached, so teardown waits for that thread to close it.
 *
 * The loop runs on an [MlnFfiRuntimeThread]. Only that thread calls a [MapHandle], apart from the
 * renderer attaching its render session. Code already on it calls the map directly; other threads
 * use [await] to get a result, [submit] when they need nothing back, and [awaitEventsDrained] to
 * run after the events raised so far have been handled.
 *
 * Queued work runs in batches between native pumps. [await] and an `ordered` [submit] end their
 * batch, so later work sees the events they raise. Other work does not, because each pump costs up
 * to [PUMP_BUDGET_MILLIS].
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

  private class DrainBarrier(val run: () -> Unit, val onDropped: () -> Unit)

  private val thread =
    MlnFfiRuntimeThread(
      name = "maplibre-compose-map",
      getLogger = getLogger,
      openRuntime = {
        MlnFfiRuntimeOwner.open(
          cacheFile,
          getLogger,
          "MapLibre runtime",
          resourceProviderFactory,
          resourceConfig,
        )
      },
      pumpBudgetMillis = PUMP_BUDGET_MILLIS,
      host = OwnerHost(),
    )

  /** Callbacks that run after the next native pump and event drain. Owner thread only. */
  private val eventDrainBarriers = mutableListOf<DrainBarrier>()

  /** The created map, published or not. Owner thread only. */
  private var created: MapHandle? = null

  private val stopSignal = MlnFfiGate()

  /** The map, once it exists. Null before creation and after teardown begins. */
  @Volatile
  var map: MapHandle? = null
    private set

  /** The first failure that stopped this loop. */
  @Volatile
  var failure: Throwable? = null
    private set

  /** The density this loop's map was created with; a change means a new loop, not a resize. */
  val scaleFactor: Double
    get() = extent.scaleFactor

  /**
   * Creates the map on a new owner thread, which runs the work queued so far before any work queued
   * later. Called at most once; a loop whose thread could not start stays stopped.
   */
  fun start(startThread: (MlnFfiOwnerThread) -> Unit = MlnFfiOwnerThread::start) {
    thread.start { owner ->
      try {
        startThread(owner)
      } catch (error: Throwable) {
        // Recorded before queued work is abandoned, so abandon callbacks can report it. The caller
        // receives the startup failure directly.
        failure = error
        throw error
      }
    }
  }

  /** Whether [start] has been called, whether or not the thread then started. */
  val isStarted: Boolean
    get() = thread.isStarted

  /**
   * Abandons every queued task and keeps accepting work, so an owner that could not create its map
   * yet can still start this loop later. Only before [start].
   */
  fun abandonQueuedTasks() {
    thread.rejectQueuedTasksBeforeStart(IllegalStateException("The map was not created"))
  }

  /** Whether the calling thread is the one that owns this loop's runtime and map. */
  fun isOwnerThread(): Boolean = thread.isCurrent()

  /**
   * Runs [action] on the owner thread and returns its result, or null when the loop stops first.
   * Rethrows what [action] throws. A caller cancelled before [action] starts skips it; with
   * [cancellable] false, [action] runs and the caller waits for it anyway. Use that for work that
   * must finish, such as work that uses native memory the caller frees once this returns.
   */
  suspend fun <T> await(cancellable: Boolean = true, action: (MapHandle) -> T): T? {
    if (thread.isCurrent()) {
      val current = map ?: return null
      thread.endBatch()
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
    if (!thread.isCurrent()) return enqueue(action, onDropped, ordered)
    val current = map ?: return onDropped()
    // Requested first, like [await], so an action that throws still ends the batch.
    if (ordered) thread.endBatch()
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
    submit(onDropped = stopped) {
      eventDrainBarriers +=
        DrainBarrier(run = { completion.complete(runCatching(action)) }, stopped)
    }
    completion.await().getOrThrow()
  }

  /** The owner thread logs what [run] throws and then runs [onDropped]. */
  private fun enqueue(run: (MapHandle) -> Unit, onDropped: () -> Unit, ordered: Boolean) {
    val accepted =
      thread.post(
        MlnFfiRuntimeThread.Task(
          run = { run(checkNotNull(created)) },
          reject = { onDropped() },
          endsBatch = ordered,
        )
      )
    if (!accepted) onDropped()
  }

  /**
   * Rejects new work and requests destruction. The caller must first release every render session
   * not owned by [onMapClosing]. [awaitClosed] acknowledges actual map and runtime destruction. A
   * loop that never started has nothing to destroy: this abandons its queued work instead, and
   * [awaitClosed] returns at once.
   */
  override fun close() {
    stopSignal.open()
    thread.stop()
  }

  suspend fun awaitClosed() = thread.awaitStopped()

  private inner class OwnerHost : MlnFfiRuntimeThread.Host {
    override fun onStarted(runtime: RuntimeHandle) {
      val map = MapHandle.create(runtime, mapOptions())
      created = map
      onMapCreated(map)
      this@MlnFfiMapRuntimeLoop.map = map
      onMapPublished(map)
      // The renderer cannot attach until a map exists, and nothing else will tell it one now does.
      requestFrame()
    }

    override fun afterPump(runtime: RuntimeHandle) {
      drainEvents(runtime, checkNotNull(created))
    }

    override fun onLoopFailure(error: Throwable, runtime: RuntimeHandle?): Throwable {
      logger?.e(error) {
        if (runtime == null) "Could not create the MapLibre runtime"
        else "The MapLibre map runtime loop failed"
      }
      val firstFailure = failure == null
      failure = failure ?: error
      if (firstFailure) runCatching { onFailure(error) }
      return error
    }

    // Map tasks abandon without a reason.
    override fun stopReason(): Throwable = IllegalStateException("The map runtime loop stopped")

    override fun onStopping(runtime: RuntimeHandle?, failures: MutableList<Throwable>) {
      abandonDrainBarriers()
      // The renderer republishes the failure, but only from a frame, and close() waits for it.
      if (failure != null) runCatching { requestFrame() }
      if (runtime == null) return
      // A failed loop still owns its map until the renderer acknowledges release via close().
      stopSignal.awaitUntilOpen()
      val closing = created ?: return
      runCatching { onMapClosing(closing) }.exceptionOrNull()?.let(failures::add)
      map = null
      created = null
      runCatching { closing.close() }.exceptionOrNull()?.let(failures::add)
    }

    override fun reportCleanup(failures: List<Throwable>) {
      val reported = mutableListOf<Throwable>()
      failures.forEach(reported::addCleanupFailure)
      reported.cleanupResult("Native map").getOrThrow()
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
