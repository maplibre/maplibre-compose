package org.maplibre.compose.map

import co.touchlab.kermit.Logger
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.maplibre.compose.mlnffi.MlnFfiMapExtent
import org.maplibre.compose.resource.MlnFfiRuntimeOwner
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapOptions
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.WakeSource

/**
 * Parks in the native pump until a wake arrives, rather than on a bound.
 *
 * Nothing arms a timer or file descriptor on the map's own run loop — the renderer, the thread
 * pool, and the file sources each have their own — so the wake flag is the only thing that ever has
 * progress to report, and a bound would only add empty pumps.
 */
private const val PUMP_PARK_MILLIS = -1L

/** Bound on waiting for the render session to be closed before the map is destroyed. */
private const val SHUTDOWN_WAIT_MILLIS = 5_000L

/**
 * The thread that owns one map's MapLibre runtime and map handle, and the only place calls on
 * either happen.
 *
 * The map is deliberately not on the presenting thread: the loop parks inside the native pump, so
 * style parsing, tile loads, and resource responses advance whether or not anything is drawing.
 * Camera transitions are the exception — mbgl steps them from `onDidFinishRenderingFrame`, so
 * moving the camera still takes a rendered frame.
 *
 * The render session is not this loop's: a session's owner is whichever thread attached it, and
 * native refuses to destroy a map that still has one attached, so teardown waits for the presenting
 * thread to close it.
 *
 * A runtime belongs to the thread that created it and there may be only one per thread, so this is
 * a plain [Thread] rather than a dispatcher or a pooled executor. maplibre-native-ffi#433 proposes
 * an owner thread inside the C API, which would retire this class.
 */
internal class MlnFfiMapRuntimeLoop(
  /** The extent the map is created with. Its scale factor is fixed for the map's lifetime. */
  private val extent: MlnFfiMapExtent,
  private val cachePath: Path,
  private val logger: Logger?,
  /** Runs on the owner thread once the map exists, before it is published. */
  private val onMapCreated: (MapHandle) -> Unit,
  /** Runs on the owner thread for every event this loop's runtime raises. */
  private val onEvent: (RuntimeEvent) -> Unit,
  /** Runs on the owner thread once the event queue is momentarily empty. */
  private val onEventsDrained: (MapHandle) -> Unit,
  /** Asks the host for a frame. Called from the owner thread. */
  private val requestFrame: () -> Unit,
) : AutoCloseable {

  /** Work for the owner thread, with the release path it must take if it never gets to run. */
  private class OwnerTask(val run: (MapHandle) -> Unit, val abandon: () -> Unit)

  private val tasks = LinkedBlockingQueue<OwnerTask>()

  /**
   * Guards [accepting] and [wake] together: nothing may be queued after the final drain, and
   * nothing may signal a wake source that is closing.
   */
  private val acceptLock = ReentrantLock()
  private var accepting = true
  private var wake: WakeSource? = null

  @Volatile private var stopRequested = false

  /** Owner-thread state: the runtime and everything retired before it. */
  private var runtimeOwner: MlnFfiRuntimeOwner? = null

  private val stopSignal = CountDownLatch(1)

  /** The map, once it exists. Null before creation and after teardown begins. */
  @Volatile
  var map: MapHandle? = null
    private set

  /** The first failure that stopped this loop, republished by the renderer. */
  @Volatile
  var failure: Throwable? = null
    private set

  /** The density this loop's map was created with; a change means a new loop, not a resize. */
  val scaleFactor: Double
    get() = extent.scaleFactor

  private val thread =
    Thread(::runLoop, "maplibre-compose-map").apply {
      // A parking pump ignores interruption, so close() is the only way to stop this thread.
      isDaemon = true
    }

  fun start() {
    thread.start()
  }

  /**
   * Runs [action] on the owner thread and waits for its result.
   *
   * Returns null when there is no map to run against, or when the loop stopped before the work
   * could run. Runs inline when the caller is already the owner thread, so an event handler can
   * read the map back without deadlocking on itself.
   */
  fun <T> call(action: (MapHandle) -> T): T? {
    if (Thread.currentThread() === thread) return map?.let(action)
    if (map == null) return null

    var result: Result<T>? = null
    val done = CountDownLatch(1)
    val posted =
      submit(
        run = { map ->
          result = runCatching { action(map) }
          done.countDown()
        },
        abandon = { done.countDown() },
      )
    if (!posted) return null

    try {
      done.await()
    } catch (interruption: InterruptedException) {
      Thread.currentThread().interrupt()
      logger?.w(interruption) { "Interrupted while waiting for the map's owner thread" }
      return null
    }
    return result?.getOrThrow()
  }

  /** Queues [action] for the owner thread, reporting whether it was accepted. */
  fun post(action: (MapHandle) -> Unit, abandon: () -> Unit = {}): Boolean =
    submit(run = action, abandon = abandon)

  private fun submit(run: (MapHandle) -> Unit, abandon: () -> Unit): Boolean = acceptLock.withLock {
    if (!accepting) return false
    tasks.add(OwnerTask(run, abandon))
    // Signalled under the lock so it cannot race the source's close, which would throw; safe
    // because the owner thread never holds this lock across a pump.
    wake?.signal()
    true
  }

  /**
   * Stops the loop and waits for it to finish. The caller must have closed its render session
   * first: native refuses to destroy a map that still has one attached.
   */
  override fun close() {
    stopRequested = true
    stopSignal.countDown()
    acceptLock.withLock { wake?.signal() }
    if (Thread.currentThread() === thread) return
    try {
      thread.join(SHUTDOWN_WAIT_MILLIS)
    } catch (interruption: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    if (thread.isAlive) {
      logger?.e { "The MapLibre map runtime thread did not stop within ${SHUTDOWN_WAIT_MILLIS}ms" }
    }
  }

  private fun runLoop() {
    val owner =
      try {
        MlnFfiRuntimeOwner.open(cachePath, logger, "MapLibre runtime").also { runtimeOwner = it }
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
      // Native refuses to destroy a map with a session still attached, so wait for the renderer to
      // close it — bounded, so a renderer that already stopped cannot wedge teardown.
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
      // Queued work first: a task posted before the source was published set no wake flag, so
      // draining only after a pump returns would leave it parked behind.
      runTasks(map)
      if (stopRequested) break
      check(!acceptLock.isHeldByCurrentThread) { "the pump must not run under acceptLock" }
      runtime.pump(PUMP_PARK_MILLIS)
      drainEvents(runtime, map)
    }
  }

  private fun mapOptions() =
    MapOptions().also {
      it.width = extent.width.coerceAtLeast(1)
      it.height = extent.height.coerceAtLeast(1)
      it.scaleFactor = extent.scaleFactor
    }

  private fun drainEvents(runtime: RuntimeHandle, map: MapHandle) {
    while (true) {
      val event =
        try {
          runtime.pollEvent() ?: break
        } catch (error: Throwable) {
          // pollEvent is not a pure read; on MAP_STYLE_LOADED it calls into the map, so it can
          // throw from the map rather than the runtime.
          logger?.e(error) { "Failed to poll a MapLibre runtime event" }
          break
        }
      if (event.mapSource != null && event.mapSource !== map) continue
      runCatching { onEvent(event) }
        .onFailure { logger?.e(it) { "Failed to handle MapLibre event ${event.type}" } }
    }
    runCatching { onEventsDrained(map) }
      .onFailure { logger?.e(it) { "Failed to finish handling a MapLibre event batch" } }
  }

  private fun runTasks(map: MapHandle) {
    while (true) {
      val task = tasks.poll() ?: break
      try {
        task.run(map)
      } catch (error: Throwable) {
        logger?.e(error) { "A map owner-thread task failed" }
      }
    }
  }

  /** Blocks until [close] is called, or the bound expires. */
  private fun awaitShutdown() {
    try {
      stopSignal.await(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (interruption: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private fun fail(error: Throwable) {
    failure = failure ?: error
    rejectQueuedTasks()
    // The renderer republishes the failure, but only from a frame.
    runCatching { requestFrame() }
  }

  private fun rejectQueuedTasks() {
    // Stop accepting and take the wake source out under the same lock, so the drain cannot race a
    // task that would then never run and nothing can signal a source that is about to close.
    val source = acceptLock.withLock {
      accepting = false
      wake.also { wake = null }
    }
    val abandoned = mutableListOf<OwnerTask>()
    tasks.drainTo(abandoned)
    // Released rather than run: a caller blocked in call() would otherwise never be resumed.
    abandoned.forEach { runCatching { it.abandon() } }
    // A wake source is its own native handle, so closing the runtime does not release it.
    source?.let { closing ->
      runCatching { closing.close() }
        .onFailure { logger?.w(it) { "Failed to close the map runtime's wake source" } }
    }
  }
}
