package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * The loop runs on an [MlnFfiRuntimeThread].
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

  private class DrainBarrier(val run: () -> Unit, val abandon: () -> Unit)

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
      post(
        action = { map ->
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

  /**
   * Suspends instead of blocking the caller; cancellation drops work that has not started. Like
   * [call], this ends its task batch.
   */
  suspend fun <T> await(action: (MapHandle) -> T): T? =
    suspendCancellableCoroutine { continuation ->
      val accepted =
        post(
          action = { map ->
            if (continuation.isActive) continuation.resumeWith(runCatching { action(map) })
          },
          abandon = { continuation.resume(null) },
          drainAfter = true,
        )
      if (!accepted) continuation.resume(null)
    }

  /**
   * Queues [action] for the owner thread, reporting whether it was accepted. [abandon] runs instead
   * when the loop stops before [action] runs. [drainAfter] drains the events [action] raises before
   * later queued work runs.
   */
  fun post(
    action: (MapHandle) -> Unit,
    abandon: () -> Unit = {},
    drainAfter: Boolean = false,
  ): Boolean =
    thread.post(
      MlnFfiRuntimeThread.Task(
        run = {
          try {
            action(checkNotNull(created))
          } catch (error: Throwable) {
            // Logged, not abandoned: the task already started.
            logger?.e(error) { "A map owner-thread task failed" }
          }
        },
        reject = { abandon() },
        endsBatch = drainAfter,
      )
    )

  /** Keeps nested writes inside an owner commit; other callers enqueue their work. */
  fun dispatch(action: (MapHandle) -> Unit, abandon: () -> Unit = {}): Boolean {
    if (!thread.isCurrent()) return post(action, abandon)
    val current = map ?: return false
    action(current)
    return true
  }

  /** Queues a callback that runs after the next native pump and event drain. */
  fun postEventDrainBarrier(action: () -> Unit, abandon: () -> Unit = {}): Boolean =
    post(action = { eventDrainBarriers += DrainBarrier(action, abandon) }, abandon = abandon)

  /**
   * Rejects new work and requests destruction. The caller must first release every render session
   * not owned by [onMapClosing]. [awaitClosed] acknowledges actual map and runtime destruction.
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
    barriers.forEach { runCatching { it.run() } }
  }

  private fun abandonDrainBarriers() {
    val barriers = eventDrainBarriers.toList()
    eventDrainBarriers.clear()
    barriers.forEach { runCatching { it.abandon() } }
  }
}
