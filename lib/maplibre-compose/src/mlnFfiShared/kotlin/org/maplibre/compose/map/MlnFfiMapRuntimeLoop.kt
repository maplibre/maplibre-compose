package org.maplibre.compose.map

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiOwnerLock
import org.maplibre.compose.mlnffi.MlnFfiOwnerThread
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.resource.MlnFfiResourceProvider
import org.maplibre.compose.resource.MlnFfiResourceProviderFactory
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapOptions
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventMask
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.WakeSource

/** Parks in the native pump until a wake arrives, rather than on a bound. */
private const val PUMP_PARK_MILLIS = -1L

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
 * This loop uses a dedicated [MlnFfiOwnerThread] rather than a dispatcher or a pooled executor.
 * maplibre-native-ffi#433 proposes an owner thread inside the C API, which would retire this class.
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
) : AutoCloseable {

  private val logger: Logger?
    get() = getLogger()

  /** Work for the owner thread; [OwnerTask.abandon] runs instead if it never gets to run. */
  private class OwnerTask(val run: (MapHandle) -> Unit, val abandon: () -> Unit)

  /**
   * The owner thread. A parked pump ignores interruption, so [close] is the only way to stop it.
   */
  private val thread = MlnFfiOwnerThread("maplibre-compose-map", ::runLoop)

  /**
   * Guards [tasks], [accepting], and [wake] together: nothing may be queued after the final drain,
   * and nothing may signal a wake source that is closing.
   */
  private val acceptLock = MlnFfiOwnerLock(thread)
  private val tasks = ArrayDeque<OwnerTask>()
  /** Test callbacks that run after the next native pump and event drain. Owner thread only. */
  private val eventDrainBarriers = mutableListOf<() -> Unit>()
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

  /** The density this loop's map was created with; a change means a new loop, not a resize. */
  val scaleFactor: Double
    get() = extent.scaleFactor

  fun start() {
    thread.start()
  }

  /** Whether the calling thread is the one that owns this loop's runtime and map. */
  fun isOwnerThread(): Boolean = thread.isCurrent()

  /**
   * Runs [action] on the owner thread and waits for its result. Returns null when there is no map,
   * or when the loop stopped before the work could run. Runs inline when the caller is already the
   * owner thread.
   */
  fun <T> call(action: (MapHandle) -> T): T? {
    if (thread.isCurrent()) return map?.let(action)
    if (map == null) return null

    var result: Result<T>? = null
    val done = MlnFfiGate()
    val posted =
      submit(
        run = { map ->
          result = runCatching { action(map) }
          done.open()
        },
        abandon = { done.open() },
      )
    if (!posted) return null

    done.await()
    // Null when the wait ended before the owner thread reached the task, which the gate's own
    // documentation allows.
    return result?.getOrThrow()
  }

  /** Queues [action] for the owner thread, reporting whether it was accepted. */
  fun post(action: (MapHandle) -> Unit, abandon: () -> Unit = {}): Boolean =
    submit(run = action, abandon = abandon)

  /** Queues a test callback that runs after the next native pump and event drain. */
  fun postEventDrainBarrierForTest(action: () -> Unit): Boolean =
    post(action = { eventDrainBarriers += action })

  private fun submit(run: (MapHandle) -> Unit, abandon: () -> Unit): Boolean = acceptLock.withLock {
    if (!accepting) return false
    tasks.add(OwnerTask(run, abandon))
    // Signalled under the lock so it cannot race the source's close, which would throw.
    wake?.signal()
    true
  }

  /**
   * Stops the loop and waits for it to finish. The caller must have closed its render session
   * first: native refuses to destroy a map that still has one attached.
   */
  override fun close() {
    stopRequested = true
    stopSignal.open()
    acceptLock.withLock { wake?.signal() }
    if (thread.isCurrent()) return
    if (!thread.join(SHUTDOWN_WAIT_MILLIS)) {
      logger?.e { "The MapLibre map runtime thread did not stop within ${SHUTDOWN_WAIT_MILLIS}ms" }
    }
  }

  private fun runLoop() {
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
      rejectQueuedTasks()
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
    val source = runtime.acquireWakeSource()
    acceptLock.withLock { wake = source }
    while (!stopRequested) {
      // Queued work first: a task posted before the source was published set no wake flag.
      val ranTasks = runTasks(map)
      if (stopRequested) break
      check(!acceptLock.isHeldByOwnerThread) { "the pump must not run under acceptLock" }
      // A batch that ran must not park: a task queuing nothing for native has nothing to wake it.
      // TODO: pass a time budget to runtime.pump once the C API accepts one, so a long tile-load
      // mailbox cannot delay queued gesture work.
      runtime.pump(if (ranTasks) 0L else PUMP_PARK_MILLIS)
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
      val task = acceptLock.withLock { tasks.removeFirstOrNull() } ?: break
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
    // A wake source is its own native handle, so closing the runtime does not release it.
    source?.let { closing ->
      runCatching { closing.close() }
        .onFailure { logger?.w(it) { "Failed to close the map runtime's wake source" } }
    }
  }
}
