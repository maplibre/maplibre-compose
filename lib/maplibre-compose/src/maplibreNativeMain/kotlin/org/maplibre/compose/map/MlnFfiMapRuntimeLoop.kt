package org.maplibre.compose.map

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiOwnerLoop
import org.maplibre.compose.resource.MlnFfiResourceProvider
import org.maplibre.compose.resource.MlnFfiResourceProviderFactory
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapOptions
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventMask
import org.maplibre.nativeffi.runtime.RuntimeHandle

/** Parks in the native pump until a wake arrives, rather than on a bound. */
private const val PUMP_PARK_MILLIS = -1L

/**
 * Caps one native drain below a 120 Hz frame so posted gesture work runs before the next vsync. The
 * first queued task always runs; leftover work re-arms the wake flag.
 */
private const val PUMP_BUDGET_MILLIS = 4L

/** Bound on waiting for the render session to be closed before the map is destroyed. */
private const val SHUTDOWN_WAIT_MILLIS = 5_000L

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
 * This loop uses a dedicated [MlnFfiOwnerLoop] thread rather than a dispatcher or a pooled
 * executor. maplibre-native-ffi#433 proposes an owner thread inside the C API, which would retire
 * this class.
 */
internal class MlnFfiMapRuntimeLoop(
  /** The extent the map is created with. Its scale factor is fixed for the map's lifetime. */
  private val extent: MapExtent,
  private val cacheFile: Path,
  private val getLogger: () -> Logger?,
  private val resourceProviderFactory: MlnFfiResourceProviderFactory = ::MlnFfiResourceProvider,
  /** Runs on the owner thread once the map exists, before it is published. */
  private val onMapCreated: (MapHandle) -> Unit,
  /** Runs on the owner thread for every event this loop's runtime raises. */
  private val onEvent: (RuntimeEvent) -> Unit,
  /** Runs on the owner thread once the event queue is momentarily empty. */
  private val onEventsDrained: (MapHandle) -> Unit,
  /** Asks the host for a frame. Called from the owner thread. */
  private val requestFrame: () -> Unit,
  private val mapEventMask: RuntimeEventMask? = null,
) : MlnFfiOwnerLoop<MlnFfiMapRuntimeLoop.OwnerTask>("maplibre-compose-map"), AutoCloseable {

  private val logger: Logger?
    get() = getLogger()

  override val loopLogger: Logger?
    get() = getLogger()

  /** Work for the owner thread; [OwnerTask.abandon] runs instead when the work never does. */
  class OwnerTask(val run: (MapHandle) -> Unit, val abandon: () -> Unit)

  /** Test callbacks that run after the next native pump and event drain. Owner thread only. */
  private val eventDrainBarriers = mutableListOf<() -> Unit>()

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

  /** The density this loop's map was created with; a change means a new loop, not a resize. */
  val scaleFactor: Double
    get() = extent.scaleFactor

  /**
   * Runs [action] on the owner thread and waits until it has run or been dropped. Returns null when
   * there is no map, or when the loop stopped before the work could run. Runs inline when the
   * caller is already the owner thread.
   *
   * [abandon] runs when [action] will not run: the loop has already stopped, or a queued task is
   * dropped. An interrupt on the waiting thread does not drop the work. The wait continues, and the
   * interrupt status is restored when this returns.
   */
  fun <T> call(action: (MapHandle) -> T, abandon: () -> Unit = {}): T? {
    if (isOwnerThread()) {
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
        OwnerTask(
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
        )
      )
    if (!posted) {
      abandon()
      return null
    }

    done.awaitUntilOpen()
    return result?.getOrThrow()
  }

  /** Queues [action] for the owner thread, reporting whether it was accepted. */
  fun post(action: (MapHandle) -> Unit, abandon: () -> Unit = {}): Boolean =
    submit(OwnerTask(action, abandon))

  /** Queues a test callback that runs after the next native pump and event drain. */
  fun postEventDrainBarrierForTest(action: () -> Unit): Boolean =
    post(action = { eventDrainBarriers += action })

  /**
   * Stops the loop and waits for it to finish. The caller must have closed its render session
   * first: native refuses to destroy a map that still has one attached.
   */
  override fun close() {
    // The stop flag must be visible before the gate opens, or the owner thread can run one more
    // task batch and pump after the close began.
    requestStop()
    stopSignal.open()
    if (isOwnerThread()) return
    if (!awaitStopped(SHUTDOWN_WAIT_MILLIS)) {
      logger?.e { "The MapLibre map runtime thread did not stop within ${SHUTDOWN_WAIT_MILLIS}ms" }
    }
  }

  override fun runLoop() {
    val owner =
      try {
        MlnFfiRuntimeOwner.open(
            cacheFile,
            getLogger,
            "MapLibre runtime",
            resourceProviderFactory,
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
      // The renderer cannot attach until a map exists, and nothing else will tell it one now does.
      requestFrame()
      pump(runtime, created)
    } catch (error: Throwable) {
      logger?.e(error) { "The MapLibre map runtime loop failed" }
      fail(error)
    } finally {
      map = null
      awaitShutdown()
      drainQueuedTasks { it.abandon() }
      try {
        runCatching { created?.close() }
          .onFailure { logger?.e(it) { "Failed to close the MapLibre map" } }
      } finally {
        // Retires the resource provider before the runtime that owns it.
        owner.close()
        runtimeOwner = null
      }
    }
  }

  private fun pump(runtime: RuntimeHandle, map: MapHandle) {
    publishWakeSource(runtime.acquireWakeSource())
    while (!stopRequested) {
      // Queued work first: a task posted before the source was published set no wake flag.
      val ranTasks = runTasks(map)
      if (stopRequested) break
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
      mapEventMask?.let { mask -> it.eventMask = mask }
    }

  private fun drainEvents(runtime: RuntimeHandle, map: MapHandle) {
    val events =
      try {
        runtime.drainEvents().events
      } catch (error: Throwable) {
        // drainEvents is not a pure read; on MAP_STYLE_LOADED it calls into the map, so it can
        // throw from the map rather than the runtime.
        logger?.e(error) { "Failed to drain MapLibre runtime events" }
        emptyList()
      }
    for (event in events) {
      if (event.mapSource != null && event.mapSource !== map) continue
      runCatching { onEvent(event) }
        .onFailure { logger?.e(it) { "Failed to handle MapLibre event ${event.type}" } }
    }
    runCatching { onEventsDrained(map) }
      .onFailure { logger?.e(it) { "Failed to finish handling a MapLibre event batch" } }
    val barriers = eventDrainBarriers.toList()
    eventDrainBarriers.clear()
    barriers.forEach { runCatching(it) }
  }

  /** Runs everything queued, reporting whether anything ran. */
  private fun runTasks(map: MapHandle): Boolean {
    var ran = false
    while (true) {
      // Taken one at a time, and run outside the lock: a task posts, closes, and calls back.
      val task = takeQueuedTask() ?: break
      ran = true
      try {
        task.run(map)
      } catch (error: Throwable) {
        logger?.e(error) { "A map owner-thread task failed" }
      }
    }
    return ran
  }

  /** Blocks until [close] is called, or the bound expires. */
  private fun awaitShutdown() {
    stopSignal.await(SHUTDOWN_WAIT_MILLIS)
  }

  private fun fail(error: Throwable) {
    failure = failure ?: error
    drainQueuedTasks { it.abandon() }
    // The renderer republishes the failure, but only from a frame.
    runCatching { requestFrame() }
  }
}
