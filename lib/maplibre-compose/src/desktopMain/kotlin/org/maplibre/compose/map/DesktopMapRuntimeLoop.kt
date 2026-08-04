package org.maplibre.compose.map

import co.touchlab.kermit.Logger
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.maplibre.compose.desktop.DesktopMapExtent
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.offline.AmbientCacheSizeRequest
import org.maplibre.compose.resource.DesktopResourceProvider
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapOptions
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions
import org.maplibre.nativeffi.runtime.WakeSource

/**
 * How long a park lasts before the loop pumps regardless of any wake.
 *
 * The runtime's wake flag covers style, tile, and resource responses, queued events, and this
 * loop's own [WakeSource], and every one of those returns the pump immediately — so this is a
 * backstop rather than the cadence. It is bounded rather than indefinite because timers and ready
 * sockets set the flag only when they queue owner-thread work.
 */
private const val PUMP_PARK_MILLIS = 100L

/** Bound on waiting for the render session to be closed before the map is destroyed. */
private const val SHUTDOWN_WAIT_MILLIS = 5_000L

/**
 * The thread that owns one map's MapLibre runtime and map handle, and the only place calls on
 * either happen.
 *
 * The map is deliberately not on the thread that presents it. MapLibre advances only while its
 * runtime is pumped, so a map pumped from a Compose draw pass makes no progress unless something is
 * asking for frames — which is why the earlier implementation had to keep requesting frames until
 * MapLibre said it was idle, and why a style mutation made after that needed its own wake. Here the
 * loop parks inside the native pump and takes its cadence from MapLibre's own work, so style
 * parsing, tile loads, and resource responses advance whether or not anything is drawing.
 *
 * Camera transitions are the exception, and not one this loop can remove: mbgl steps a transition
 * from `onDidFinishRenderingFrame` while `transform.inTransition()`, so it still takes a rendered
 * frame to move the camera.
 *
 * The render session is not this loop's. Since maplibre-native-ffi #399 a session's owner is
 * whichever thread attached it, so the presenting thread attaches, renders, and closes its own
 * session against the map published here. Native refuses to destroy a map that still has one
 * attached, which is why teardown waits for that thread to close it.
 *
 * A runtime belongs to the thread that created it and there may be only one per thread, so this is
 * a plain [Thread] rather than a dispatcher or a pooled executor.
 */
internal class DesktopMapRuntimeLoop(
  /** The extent the map is created with. Its scale factor is fixed for the map's lifetime. */
  private val extent: DesktopMapExtent,
  private val runtimeOptions: DesktopRuntimeOptions,
  private val logger: Logger?,
  /** Runs on the owner thread once the map exists, before it is published. */
  private val onMapCreated: (MapHandle) -> Unit,
  /** Runs on the owner thread for every event this loop's runtime raises. */
  private val onEvent: (RuntimeEvent) -> Unit,
  /** Runs on the owner thread once the event queue is momentarily empty. */
  private val onEventsDrained: () -> Unit,
  /** Asks the host for a frame. Called from the owner thread. */
  private val requestFrame: () -> Unit,
  /**
   * Injectable so a test can park long enough that a missing wake fails rather than passes late.
   */
  private val parkMillis: Long = PUMP_PARK_MILLIS,
) : AutoCloseable {

  /** Work for the owner thread, with the release path it must take if it never gets to run. */
  private class OwnerTask(val run: (MapHandle) -> Unit, val abandon: () -> Unit)

  private val tasks = LinkedBlockingQueue<OwnerTask>()

  /**
   * Guards [accepting] and [wake] together, so that a task cannot be queued after the queue has
   * been drained for the last time, and a signal cannot race the wake source's close.
   */
  private val acceptLock = ReentrantLock()
  private var accepting = true
  private var wake: WakeSource? = null

  @Volatile private var stopRequested = false

  /**
   * The ambient cache budget being applied. Owner-thread state; retired by its completion event.
   */
  private var cacheSizeRequest: AmbientCacheSizeRequest? = null

  /** Released by [close], so teardown can wait for it without polling. */
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
      // The pump must never keep a shutting-down application alive; disposal is what stops it. A
      // parking pump also ignores interruption, so close() is the only way to stop this thread.
      isDaemon = true
    }

  fun start() {
    thread.start()
  }

  /**
   * Runs [action] on the owner thread and waits for its result.
   *
   * Returns null when there is no map to run against, or when the loop stopped before the work
   * could run — the same "there is nothing to answer with" the callers already handle. Running
   * inline when the caller is already the owner thread is what lets an event handler read the map
   * back without deadlocking on itself.
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
  fun post(action: (MapHandle) -> Unit): Boolean = submit(run = action, abandon = {})

  private fun submit(run: (MapHandle) -> Unit, abandon: () -> Unit): Boolean = acceptLock.withLock {
    if (!accepting) return false
    tasks.add(OwnerTask(run, abandon))
    // Signalled under the lock so it cannot race the source's close, which would throw. This is
    // safe in the direction that matters: the owner thread never holds this lock across a pump.
    wake?.signal()
    true
  }

  /**
   * Stops the loop and waits for it to finish.
   *
   * The caller must have closed its render session first: native refuses to destroy a map that
   * still has one attached, and this joins the thread that destroys it.
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
    val runtime =
      try {
        createRuntime()
      } catch (error: Throwable) {
        logger?.e(error) { "Could not create the MapLibre runtime" }
        fail(error)
        return
      }

    var created: MapHandle? = null
    try {
      created = MapHandle.create(runtime, mapOptions())
      onMapCreated(created)
      map = created
      // The renderer cannot attach until a map exists, and nothing else will tell it that one now
      // does.
      requestFrame()
      pump(runtime, created)
    } catch (error: Throwable) {
      logger?.e(error) { "The MapLibre map runtime loop failed" }
      fail(error)
    } finally {
      map = null
      // The renderer owns the session and only learns of a failure on a later frame. Native
      // refuses to destroy a map that still has one attached, so wait for the renderer to close
      // it — bounded, so a renderer that already stopped cannot wedge teardown.
      awaitShutdown()
      rejectQueuedTasks()
      try {
        runCatching { created?.close() }
          .onFailure { logger?.e(it) { "Failed to close the MapLibre map" } }
      } finally {
        // Before the runtime, because RuntimeHandle.close() blocks on operations still in flight,
        // and cancelling a budget nothing will observe again is what should happen here.
        retireCacheSizeRequest()
        runCatching { runtime.close() }
          .onFailure { logger?.e(it) { "Failed to close the MapLibre runtime" } }
      }
    }
  }

  private fun pump(runtime: RuntimeHandle, map: MapHandle) {
    val source = runtime.acquireWakeSource()
    acceptLock.withLock { wake = source }
    while (!stopRequested) {
      // Queued work first. A task posted before the source was published set no wake flag, and the
      // pump below clears the flag before it drains, so checking the queue only after a pump
      // returns is what would leave such a task parked behind.
      runTasks(map)
      if (stopRequested) break
      check(!acceptLock.isHeldByCurrentThread) { "the pump must not run under acceptLock" }
      runtime.pump(parkMillis)
      drainEvents(runtime, map)
    }
  }

  private fun createRuntime(): RuntimeHandle {
    // Created eagerly: MapLibre opens the database when the runtime is created and fails if the
    // directory is missing, which on a fresh machine it always is.
    runCatching { runtimeOptions.cachePath.parent?.let(Files::createDirectories) }
      .onFailure { logger?.w(it) { "Could not create the MapLibre cache directory" } }

    val runtime =
      RuntimeHandle.create(
        RuntimeOptions().also { it.cachePath = runtimeOptions.cachePath.toString() }
      )
    return try {
      // Started before the provider, so the budget is in force before any response can be cached
      // against it. The answer arrives as an event, which drainEvents retires.
      cacheSizeRequest =
        AmbientCacheSizeRequest.start(runtime, runtimeOptions.maximumCacheSizeBytes, logger)
      // Installed with the runtime, before the map exists, so no resource a map requests can be
      // issued before the provider that serves it.
      runtime.setResourceProvider(DesktopResourceProvider(logger))
      logger?.i { "Created MapLibre runtime on ${Thread.currentThread().name}" }
      runtime
    } catch (error: Throwable) {
      // Anything after create must close the runtime on the way out, or its scheduler and database
      // connection stay open for the life of the process. The cache-size operation goes first,
      // because closing a runtime with an operation outstanding is what it is guarding against.
      retireCacheSizeRequest()
      runCatching { runtime.close() }
        .onFailure { logger?.e(it) { "Failed to close the runtime after a failed setup" } }
      throw error
    }
  }

  private fun retireCacheSizeRequest() {
    cacheSizeRequest?.close()
    cacheSizeRequest = null
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
      // Runtime-owned bookkeeping, not something the session should see: this loop started the
      // operation, so this loop retires it.
      if (cacheSizeRequest?.consume(event) == true) {
        cacheSizeRequest = null
        continue
      }
      runCatching { onEvent(event) }
        .onFailure { logger?.e(it) { "Failed to handle MapLibre event ${event.type}" } }
    }
    runCatching { onEventsDrained() }
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
    // The renderer republishes the failure, but only from a frame, and a loop that failed will
    // never ask for one again.
    runCatching { requestFrame() }
  }

  private fun rejectQueuedTasks() {
    // Stop accepting first, so the drain below cannot race a task that would then never run, and
    // take the wake source out in the same breath so nothing can signal one that is about to close.
    val source = acceptLock.withLock {
      accepting = false
      wake.also { wake = null }
    }
    val abandoned = mutableListOf<OwnerTask>()
    tasks.drainTo(abandoned)
    // Released rather than run: a caller blocked in call() is waiting on this and would otherwise
    // never be resumed.
    abandoned.forEach { runCatching { it.abandon() } }
    // A wake source is its own native handle and outlives its runtime, so closing the runtime does
    // not release it; the leak cleaner reports one that is dropped.
    source?.let { closing ->
      runCatching { closing.close() }
        .onFailure { logger?.w(it) { "Failed to close the map runtime's wake source" } }
    }
  }
}
