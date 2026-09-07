@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.mlnffi

import androidx.compose.ui.unit.LayoutDirection
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MlnFfiMapSession
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.RecordingMapCallbacks
import org.maplibre.compose.testing.RgbaPixel

/**
 * Runs a real [MlnFfiMapSession] against the packaged runtime and production presentation bridge,
 * without a Compose composition. Frames are driven explicitly by the caller.
 */
internal class BridgeMapFixture
private constructor(
  private val driver: FfiTestRenderDriver,
  private val cacheFile: Path,
  private val initialExtent: MapExtent,
  resourceConfig: MapResourceConfig,
) : AutoCloseable {

  private val stylePublishedBeforeSessionReady = AtomicBoolean(false)
  private val recorder = RecordingMapCallbacks { map, style ->
    if (style != null && (map as MlnFfiMapSession).loadedStyleIdentity !== style.identity) {
      stylePublishedBeforeSessionReady.store(true)
    }
  }

  val events: RecordingList<String>
    get() = recorder.events

  val engineEvents: RecordingList<MapEvent>
    get() = recorder.engineEvents

  val sourceChanges: RecordingList<String?>
    get() = recorder.sourceChanges

  val errors: RecordingList<String>
    get() = recorder.errors

  /** The live style, once one has loaded. */
  val style: StyleBinding?
    get() = recorder.style

  private var frameId = 0L
  private var frameRequested = true
  private val runtime = mapRuntimeForTest()
  val state = runtime.createMapState(BaseStyle.Demo)

  val session: MlnFfiMapSession =
    MlnFfiMapSession(
      lifecycleAuthority = state.lifecycle,
      callbacks = recorder,
      logger = MapLog,
      renderBackend = driver.backends.producer,
      scaleFactor = initialExtent.scaleFactor,
      layoutDirection = LayoutDirection.Ltr,
      cacheFile = cacheFile,
      resourceConfig = resourceConfig,
    )

  fun bindState(state: MapState) {
    recorder.attachment = requireNotNull(state.currentMapAttachment)
    recorder.state = state
  }

  /** The thread [whileRenderingOnRendererThread] drives frames from, while one is running. */
  @Volatile private var rendererThread: RendererThread? = null

  private val hostSession =
    object : MlnFfiMapHostSession {
      override val backends: RenderBackendPair = driver.backends

      override fun requestFrame() {
        frameRequested = true
      }

      override fun <T> withRendererAccess(action: () -> T): T {
        val thread = rendererThread ?: return driver.withRendererAccess(action)
        return thread.run(action)
      }

      override fun enqueueRenderer(action: () -> Unit): Boolean {
        val thread = rendererThread
        if (thread == null) driver.withRendererAccess(action) else thread.post(action)
        return true
      }
    }

  init {
    session.start()
    session.onSurfaceAvailable(hostSession)
  }

  /**
   * Takes the surface away, as a host does when its device is lost. Only the render session and its
   * target go; the map, its style, and its camera survive.
   */
  fun loseSurface() {
    session.onSurfaceLost()
    forgetPresentedFrame()
  }

  /** Hands the surface back, and forgets that anything was ever rendered into the old one. */
  fun restoreSurface() {
    forgetPresentedFrame()
    session.onSurfaceAvailable(hostSession)
  }

  private fun forgetPresentedFrame() {
    hasRendered = false
    driver.discardPresentedFrame()
  }

  val attachCount: Int
    get() = session.attachCount

  /**
   * Whether MapLibre has rendered at least once, which is how a test knows the map exists and is
   * attached. The runtime and map are created on their own thread, so the first frame is not it.
   */
  @Volatile
  var hasRendered: Boolean = false
    internal set

  /** Renders one frame, exactly as [MlnFfiMapSurface] does inside its draw pass. */
  fun frame(extent: MapExtent = initialExtent): MlnFfiFrameResult {
    frameRequested = false
    val frame =
      when (val acquisition = driver.acquireFrame(frameId++, extent, null)) {
        is MlnFfiMapFrameAcquisition.Acquired -> acquisition.frame
        MlnFfiMapFrameAcquisition.NotReady ->
          error("The production ${driver.backends} bridge had no test GPU context")
      }
    return try {
      driver
        .withProducerAccess(frame) { session.render(frame) }
        .also {
          if (it == MlnFfiFrameResult.RENDERED) {
            driver.completeProducerAccess(frame)
            check(driver.present(frame.target)) {
              "The production ${driver.backends} bridge did not present frame ${frame.frameId}"
            }
            hasRendered = true
          }
        }
    } finally {
      driver.releaseFrame(frame)
    }
  }

  /** Reads one rendered RGBA pixel back from the platform/backend target. */
  fun readPixel(x: Int, y: Int): RgbaPixel =
    checkNotNull(tryReadPixel(x, y)) { "No production bridge frame has been presented" }

  /**
   * One rendered pixel after the current surface has presented a frame, or null.
   *
   * [readPixel] requires that presentation. A [pumpUntil] condition uses this so a skipped frame
   * waits for the next one.
   */
  fun tryReadPixel(x: Int, y: Int): RgbaPixel? = if (hasRendered) driver.readPixel(x, y) else null

  /** Renders frames until MapLibre has drawn once, so the map is known to exist. */
  fun pumpUntilRendered(extent: MapExtent = initialExtent, timeout: Duration = 30.seconds) {
    pumpUntil("the map to render its first frame", timeout, extent) { hasRendered }
  }

  /**
   * Renders frames until [condition] holds, or fails. mbgl advances a camera transition from
   * `onDidFinishRenderingFrame`, so a transition that renders no frames stalls after its first
   * step.
   */
  fun pumpUntil(
    description: String,
    timeout: Duration = 30.seconds,
    extent: MapExtent = initialExtent,
    condition: suspend () -> Boolean,
  ) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    var frames = 0
    while (!runBlocking { condition() }) {
      check(deadline.hasNotPassedNow()) {
        "Timed out after $frames frames waiting for $description. Errors: $errors"
      }
      frame(extent)
      frames++
      // A tight loop would starve the network and worker threads.
      parkForTest(POLL_INTERVAL_MILLIS)
    }
  }

  /**
   * Renders for [duration], but only when the session asks for a frame, and reports how many it
   * drew. Only an on-demand loop can measure whether a map is at rest; an unconditional pump
   * sustains and measures itself.
   */
  fun renderOnDemand(duration: Duration): Int {
    val deadline = TimeSource.Monotonic.markNow() + duration
    var rendered = 0
    while (deadline.hasNotPassedNow()) {
      if (frameRequested && frame() == MlnFfiFrameResult.RENDERED) rendered++
      parkForTest(POLL_INTERVAL_MILLIS)
    }
    return rendered
  }

  /** Renders on demand until nothing has been asked for across [quiet], or fails. */
  fun settle(quiet: Duration = 500.milliseconds, timeout: Duration = 30.seconds) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (renderOnDemand(quiet) > 0) {
      check(deadline.hasNotPassedNow()) {
        "Timed out waiting for the map to stop asking for frames. Errors: $errors"
      }
    }
  }

  /** Renders a fixed number of frames, letting anything queued make progress. */
  fun pump(frames: Int = 30) {
    repeat(frames) {
      frame()
      parkForTest(POLL_INTERVAL_MILLIS)
    }
  }

  /**
   * Runs [block] on this thread while a dedicated renderer thread drives frames, and returns its
   * result.
   *
   * Renderer access requested from any other thread is serialized onto that thread the way a
   * platform host serializes it onto its own, in the order that exposes bookkeeping the session
   * keeps off that thread: the access runs after a frame that started after the request, as it does
   * behind a frame the host had already posted. A frame that fails, or a frame that this call ends
   * while an access is still queued, fails the call.
   */
  fun <T> whileRenderingOnRendererThread(block: () -> T): T {
    val thread = RendererThread(driver) { frame() }
    rendererThread = thread
    thread.start()
    val result = runCatching(block)
    rendererThread = null
    val renderFailure = thread.stop()
    result.exceptionOrNull()?.let { failure ->
      renderFailure?.let(failure::addSuppressed)
      throw failure
    }
    renderFailure?.let { throw it }
    return result.getOrThrow()
  }

  /** Runs [block] on another thread while this one renders frames, and returns its result. */
  fun <T> awaitWhileRendering(
    description: String,
    timeout: Duration = 30.seconds,
    block: suspend () -> T,
  ): T = runBlocking {
    val work = async(Dispatchers.Default) { block() }
    pumpUntil(description, timeout) { work.isCompleted }
    work.await()
  }

  /**
   * Applies a style and pumps until that load finishes.
   *
   * [MlnFfiMapSession.setBaseStyle] clears the live style before the new document loads, so this
   * waits for a `STYLE_LOADED` that arrives after the call.
   */
  fun loadStyle(
    style: BaseStyle,
    timeout: Duration = 60.seconds,
    extent: MapExtent = DEFAULT_EXTENT,
  ) {
    val styleLoadsBefore = events.count { it == STYLE_LOADED }
    session.setBaseStyle(style)
    if (this.style?.isLoaded != true) {
      pumpUntil("style $style to load", timeout, extent) {
        events.count { it == STYLE_LOADED } > styleLoadsBefore && this.style != null
      }
    }
    check(!stylePublishedBeforeSessionReady.load()) {
      "The loaded style callback ran before the native session stored its binding"
    }
  }

  /**
   * Loads [style] while leaving the render session unattached until the caller requests a frame.
   */
  fun loadStyleBeforeRendering(style: BaseStyle, timeout: Duration = 60.seconds) {
    val styleLoadsBefore = events.count { it == STYLE_LOADED }
    session.setBaseStyle(style)
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (events.count { it == STYLE_LOADED } <= styleLoadsBefore || this.style == null) {
      check(deadline.hasNotPassedNow()) {
        "Timed out waiting for style $style to load before rendering. Errors: $errors"
      }
      parkForTest(POLL_INTERVAL_MILLIS)
    }
    check(!stylePublishedBeforeSessionReady.load()) {
      "The loaded style callback ran before the native session stored its binding"
    }
  }

  override fun close() {
    runCatching {
      state.close()
      runBlocking { state.awaitClosed() }
      runtime.close()
      runBlocking { runtime.awaitClosed() }
    }
    runCatching { driver.close() }
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  /**
   * Drives frames until stopped, running queued renderer access between them. An access requested
   * from another thread waits for a frame that started after the request; one requested on this
   * thread runs at once.
   */
  private class RendererThread(
    private val driver: FfiTestRenderDriver,
    private val frame: () -> Unit,
  ) {
    private class Access(
      val framesStartedWhenQueued: Long,
      val run: () -> Unit,
      val done: MlnFfiGate,
    )

    private val lock = MlnFfiLock()
    private val queue = ArrayDeque<Access>()
    private var framesStarted = 0L
    private var stopped = false
    @Volatile private var stopRequested = false
    @Volatile private var failure: Throwable? = null
    private val thread = MlnFfiOwnerThread("maplibre-compose-test-renderer", ::loop)

    fun start() {
      thread.start()
    }

    fun <T> run(action: () -> T): T {
      if (thread.isCurrent()) return driver.withRendererAccess(action)
      var result: Result<T>? = null
      enqueue { result = runCatching { driver.withRendererAccess(action) } }.awaitUntilOpen()
      return checkNotNull(result) { "The test renderer thread stopped before running an access" }
        .getOrThrow()
    }

    fun post(action: () -> Unit) {
      if (thread.isCurrent()) {
        driver.withRendererAccess(action)
        return
      }
      enqueue { runCatching { driver.withRendererAccess(action) } }
    }

    /** Stops the thread and returns what failed on it, if anything. */
    fun stop(): Throwable? {
      stopRequested = true
      check(thread.join(STOP_TIMEOUT_MILLIS)) { "The test renderer thread did not stop" }
      return failure
    }

    /** Queues [run] and returns the gate that opens once it has run or been abandoned. */
    private fun enqueue(run: () -> Unit): MlnFfiGate {
      val done = MlnFfiGate()
      val accepted = lock.withLock {
        if (!stopped) queue += Access(framesStarted, run, done)
        !stopped
      }
      if (!accepted) done.open()
      return done
    }

    private fun loop() {
      try {
        while (!stopRequested) {
          runQueuedAccess()
          lock.withLock { framesStarted++ }
          frame()
          parkForTest(1L)
        }
      } catch (error: Throwable) {
        failure = error
      } finally {
        val abandoned = lock.withLock {
          stopped = true
          queue.toList().also { queue.clear() }
        }
        abandoned.forEach { it.done.open() }
      }
    }

    private fun runQueuedAccess() {
      val ready = lock.withLock {
        val eligible = queue.filter { it.framesStartedWhenQueued < framesStarted }
        queue.removeAll(eligible)
        eligible
      }
      ready.forEach { access ->
        try {
          access.run()
        } finally {
          access.done.open()
        }
      }
    }

    private companion object {
      const val STOP_TIMEOUT_MILLIS = 30_000L
    }
  }

  companion object {
    const val STYLE_LOADED: String = MapFixture.STYLE_LOADED

    private const val POLL_INTERVAL_MILLIS = 8L

    val DEFAULT_EXTENT: MapExtent = MapFixture.DEFAULT_EXTENT

    val RETINA_EXTENT: MapExtent = MapFixture.RETINA_EXTENT

    /** Creates a fixture for the one native runtime packaged into this test process. */
    fun create(
      initialExtent: MapExtent = DEFAULT_EXTENT,
      resourceConfig: MapResourceConfig = MapResourceConfig(),
    ): BridgeMapFixture {
      FfiTestPlatform.initialize()
      val driver = FfiTestPlatform.createRenderDriver()
      val cacheFile = FfiTestPlatform.createCacheFile()
      return try {
        BridgeMapFixture(driver, cacheFile, initialExtent, resourceConfig)
      } catch (error: Throwable) {
        runCatching { driver.close() }
        FfiTestPlatform.deleteCacheFile(cacheFile)
        throw error
      }
    }
  }
}
