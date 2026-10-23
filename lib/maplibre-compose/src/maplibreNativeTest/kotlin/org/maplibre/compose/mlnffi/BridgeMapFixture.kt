@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.mlnffi

import androidx.compose.ui.unit.LayoutDirection
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MlnFfiMapSession
import org.maplibre.compose.map.createNativeMapRuntime
import org.maplibre.compose.map.nativeOwner
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.RecordingMapCallbacks
import org.maplibre.compose.testing.RgbaPixel

/**
 * Runs a real [MlnFfiMapSession] against the packaged runtime and production presentation bridge,
 * without a Compose composition.
 *
 * The fixture is a host: it renders when the session asks for a frame, at display cadence, on
 * [TestMapMain], which is also the map's main thread. Create and use it from a `runMapTest` body.
 * Every wait suspends, so engine callbacks and frames run while a test waits.
 */
internal class BridgeMapFixture
private constructor(
  private val driver: FfiTestRenderDriver,
  private val cacheFile: Path,
  initialExtent: MapExtent,
  resourceConfig: MapResourceConfig,
  sharedRuntime: MapRuntime?,
  present: Boolean,
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

  val errors: RecordingList<String>
    get() = recorder.errors

  /** The live style, once one has loaded. */
  val style: StyleBinding?
    get() = recorder.style

  private val ownsRuntime = sharedRuntime == null
  private val runtime =
    sharedRuntime
      ?: createNativeMapRuntime(
        MlnFfiRuntimeOptions(
          cacheFile = cacheFile,
          mainDispatcher = TestMapMain.dispatcher,
          requestInterceptor = resourceConfig.interceptor,
          resourceProvider = resourceConfig.provider,
          logger = MapLog,
        ),
      )
  val state = runtime.createMapState(BaseStyle.Empty)

  val session: MlnFfiMapSession =
    MlnFfiMapSession(
      lifecycleAuthority = state.lifecycle,
      callbacks = recorder,
      logger = MapLog,
      renderBackend = driver.backends.producer,
      scaleFactor = initialExtent.scaleFactor,
      layoutDirection = LayoutDirection.Ltr,
      owner = runtime.nativeOwner,
    )

  fun bindState(state: MapState) {
    recorder.bindState(state)
  }

  /** The extent the next frame renders at. Changing it schedules a frame. */
  var extent: MapExtent = initialExtent
    set(value) {
      if (field == value) return
      field = value
      scheduleFrame()
    }

  /** While true, frames the session asks for wait until [frame] or until rendering resumes. */
  var renderingPaused: Boolean = false
    set(value) {
      field = value
      if (!value) scheduleFrame()
    }

  /** Simulates the host failing to dispatch renderer cleanup before it reaches the GPU context. */
  @Volatile var rendererEnqueueFailure: Throwable? = null

  /** The thread frames render on, when it is not main; see [withRendererThread]. */
  @Volatile private var rendererThread: TestThread? = null
  // The work launched onto [rendererThread], which finishes before the thread closes.
  @Volatile private var rendererJob: Job? = null
  private val rendererContext: CoroutineContext
    get() {
      val thread = rendererThread ?: return TestMapMain.dispatcher
      return thread.dispatcher + checkNotNull(rendererJob)
    }

  private val scope = CoroutineScope(SupervisorJob() + TestMapMain.dispatcher)
  private val frameScheduled = AtomicBoolean(false)
  private val frameRequested = AtomicBoolean(true)
  private var frameExtent: MapExtent? = null
  private var surfaceTarget = false
  // Written on the renderer thread and read on main.
  @Volatile private var renderFailure: Throwable? = null
  // Recoverable frame failures since the last rendered frame.
  private var recoveries = 0

  /** Counts frame visits, so a wait re-checks its condition after each one. */
  private val visits = MutableStateFlow(0L)

  /** How many frames have rendered and presented. */
  @Volatile
  var renderedFrames: Long = 0
    private set

  /**
   * Whether MapLibre has rendered at least once, which is how a test knows the map exists and is
   * attached. The runtime and map are created on their own thread, so the first frame is not it.
   */
  @Volatile
  var hasRendered: Boolean = false
    private set

  private val hostSession =
    object : MlnFfiMapHostSession {
      override val isClosed = false
      override val backends: RenderBackendPair = driver.backends

      override fun requestFrame() {
        frameRequested.store(true)
        scheduleFrame()
      }

      override fun completeConsumerAccess() = driver.completeConsumerAccess()

      override fun <T> withRendererAccess(action: () -> T): T {
        val thread = rendererThread ?: return driver.withRendererAccess(action)
        return thread.submit { driver.withRendererAccess(action) }
      }

      // Queues without waiting, as the host contract requires: native wakes the session's driver
      // work from inside a render, while a renderer thread may be waiting for that render.
      override fun enqueueRenderer(action: () -> Unit): Boolean {
        rendererEnqueueFailure?.let { throw it }
        if (rendererThread == null) return driver.enqueueRenderer(action)
        scope.launch(rendererContext) { driver.withRendererAccess(action) }
        return true
      }
    }

  init {
    recorder.bindState(state)
    if (present) {
      val token = state.reservePresentation()
      session.beginPresentationAttachment()
      state.publishPresentation(token, session)
    }
    session.onSurfaceAvailable(hostSession)
    scheduleFrame()
  }

  /** Schedules one frame visit at display cadence. Requests coalesce. Safe from any thread. */
  private fun scheduleFrame() {
    if (!frameScheduled.compareAndSet(expectedValue = false, newValue = true)) return
    scope.launch(rendererContext) {
      delay(FrameInterval)
      frameScheduled.store(false)
      if (renderingPaused) return@launch
      try {
        frame()
      } catch (error: MlnFfiRecoverableFrameException) {
        recover(error)
      } catch (error: Throwable) {
        renderFailure = renderFailure ?: error
      }
    }
  }

  /**
   * Rebuilds the render session after a recoverable frame failure, as a production host does, and
   * fails the test after the same number of attempts. Logged at info level, so a test that counts
   * warnings does not count a rebuild.
   */
  private fun recover(error: MlnFfiRecoverableFrameException) {
    if (++recoveries > MaxRenderRecoveryAttempts) {
      renderFailure = renderFailure ?: error
      return
    }
    MapLog.i(error) {
      "Test frame failed; rebuilding the render session (attempt $recoveries of $MaxRenderRecoveryAttempts)"
    }
    loseSurface()
    restoreSurface()
  }

  /**
   * Takes the surface away, as a host does when its device is lost. Only the render session and its
   * target go; the map, its style, and its camera survive.
   */
  fun loseSurface() {
    session.onSurfaceLost(hostSession)
    forgetPresentedFrame()
  }

  /** Hands the surface back, and forgets that anything was ever rendered into the old one. */
  fun restoreSurface() {
    forgetPresentedFrame()
    session.onSurfaceAvailable(hostSession)
    scheduleFrame()
  }

  private fun forgetPresentedFrame() {
    hasRendered = false
    driver.discardPresentedFrame()
  }

  val attachCount: Int
    get() = session.presentation.attachCount

  /**
   * Visits the renderer once: renders if the session asked for a frame or the extent changed, and
   * otherwise returns [MlnFfiFrameResult.AwaitUpdate].
   */
  fun frame(captureProjection: Boolean = false): MlnFfiFrameResult {
    try {
      return renderFrame(captureProjection)
    } finally {
      visits.value++
    }
  }

  private fun renderFrame(captureProjection: Boolean): MlnFfiFrameResult {
    val extent = extent
    if (frameExtent == extent && !frameRequested.load() && !captureProjection) {
      return MlnFfiFrameResult.AwaitUpdate
    }
    frameRequested.store(false)
    val frame =
      when (val acquisition = driver.acquireFrame(extent)) {
        is MlnFfiMapFrameAcquisition.Acquired -> acquisition.frame
        MlnFfiMapFrameAcquisition.NotReady ->
          error("The production ${driver.backends} bridge had no test GPU context")
      }
    frameExtent = extent
    surfaceTarget =
      when (frame.target) {
        is OpenGlSurfaceTarget,
        is MetalSurfaceTarget,
        is VulkanSurfaceTarget -> true
        else -> false
      }
    return try {
      driver
        .withProducerAccess(frame) { session.render(hostSession, frame, captureProjection) }
        .also {
          when (it) {
            is MlnFfiFrameResult.Rendered -> {
              if (!surfaceTarget) {
                driver.completeProducerAccess(frame)
                check(driver.present(frame.target)) {
                  "The production ${driver.backends} bridge did not present generation ${frame.target.generation}"
                }
              }
              hasRendered = true
              renderedFrames++
              recoveries = 0
              // A display-paced host visits again, which drains results and re-arms the demand.
              hostSession.requestFrame()
            }
            MlnFfiFrameResult.RetryNextFrame -> hostSession.requestFrame()
            MlnFfiFrameResult.AwaitUpdate -> Unit
          }
        }
    } finally {
      driver.releaseFrame(frame)
    }
  }

  /** Renders a frame that captures its projection. */
  suspend fun renderFrameProjection(): MlnFfiMapFrameProjection {
    // Automatic frames would draw the repaint first and leave the capturing frame nothing to draw.
    val wasPaused = renderingPaused
    renderingPaused = true
    try {
      session.onSurfaceChanged(extent)
      // A surface demand needs an update even when the last frame already shows this camera.
      checkNotNull(session.mapForTest).requestRepaint()
      var projection: MlnFfiMapFrameProjection? = null
      awaitUntil("a rendered frame projection") {
        val result = frame(captureProjection = true)
        if (result is MlnFfiFrameResult.Rendered) projection = result.projection
        projection != null
      }
      return checkNotNull(projection)
    } finally {
      renderingPaused = wasPaused
    }
  }

  /** Reads one pixel of the last presented frame. */
  fun readPixel(x: Int, y: Int): RgbaPixel =
    checkNotNull(tryReadPixel(x, y)) { "No production bridge frame has been presented" }

  /** One pixel of the last presented frame, or null before the current surface has presented. */
  fun tryReadPixel(x: Int, y: Int): RgbaPixel? = if (hasRendered) driver.readPixel(x, y) else null

  /**
   * Suspends until [condition] holds, or fails. The condition is checked again after each frame and
   * at least once per display frame. A camera transition and a tile load both advance from inside a
   * render, which the fixture schedules whenever the map asks.
   */
  suspend fun awaitUntil(
    description: String,
    timeout: Duration = 30.seconds,
    condition: suspend () -> Boolean,
  ) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!condition()) {
      renderFailure?.let { throw it }
      check(deadline.hasNotPassedNow()) {
        "Timed out after $renderedFrames frames waiting for $description. Errors: $errors"
      }
      val seen = visits.value
      withTimeoutOrNull(FrameInterval) { visits.first { it != seen } }
    }
    renderFailure?.let { throw it }
  }

  /** Suspends until MapLibre has drawn once, so the map is known to exist. */
  suspend fun awaitRendered(timeout: Duration = 30.seconds) {
    awaitUntil("the map to render its first frame", timeout) { hasRendered }
  }

  /** Lets the map run for about [frames] display frames. */
  suspend fun awaitFrames(frames: Int = 30) {
    delay(FrameInterval * frames)
    renderFailure?.let { throw it }
  }

  /** How many frames the map asks for and renders across [duration]. */
  suspend fun countFramesOver(duration: Duration): Long {
    val before = renderedFrames
    delay(duration)
    renderFailure?.let { throw it }
    return renderedFrames - before
  }

  /** Suspends until the map renders no frame across [quiet], or fails. */
  suspend fun awaitSettled(quiet: Duration = 500.milliseconds, timeout: Duration = 30.seconds) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (countFramesOver(quiet) > 0) {
      check(deadline.hasNotPassedNow()) {
        "Timed out waiting for the map to stop asking for frames. Errors: $errors"
      }
    }
  }

  /** Runs [block] with frames rendering on a dedicated renderer thread instead of main. */
  suspend fun <T> withRendererThread(block: suspend () -> T): T {
    check(rendererThread == null) { "Frames already render on a renderer thread" }
    val thread = TestThread("maplibre-compose-test-renderer")
    val job = SupervisorJob(scope.coroutineContext[Job])
    rendererJob = job
    rendererThread = thread
    return try {
      scheduleFrame()
      block()
    } finally {
      rendererThread = null
      withContext(NonCancellable) {
        job.cancelAndJoin()
        frameScheduled.store(false)
      }
      rendererJob = null
      thread.close()
      scheduleFrame()
    }
  }

  /** Runs [block] while frames render, failing if it does not finish within [timeout]. */
  suspend fun <T> awaitWhileRendering(
    description: String,
    timeout: Duration = 30.seconds,
    block: suspend () -> T,
  ): T =
    try {
      withTimeout(timeout) { block() }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
      throw IllegalStateException("Timed out waiting for $description. Errors: $errors", error)
    }

  private fun requestStyle(style: BaseStyle) {
    checkNotNull(state.style.asMutable).baseStyle = style
  }

  /**
   * Applies a style and suspends until that load finishes.
   *
   * [MlnFfiMapSession.setBaseStyle] clears the live style before the new document loads, so this
   * waits for a `StyleLoaded` that arrives after the call.
   */
  suspend fun loadStyle(style: BaseStyle, timeout: Duration = 60.seconds) {
    val styleLoadsBefore = events.count { it == StyleLoaded }
    requestStyle(style)
    if (this.style?.isLoaded != true) {
      awaitUntil("style $style to load", timeout) {
        events.count { it == StyleLoaded } > styleLoadsBefore && this.style != null
      }
    }
    check(!stylePublishedBeforeSessionReady.load()) {
      "The loaded style callback ran before the native session stored its binding"
    }
  }

  /** [loadStyle] with rendering paused, so the render session stays as it was until [frame]. */
  suspend fun loadStyleBeforeRendering(style: BaseStyle, timeout: Duration = 60.seconds) {
    renderingPaused = true
    loadStyle(style, timeout)
  }

  private var closed = false

  /**
   * Closes the map and, when the fixture created it, the runtime. The asynchronous part finishes
   * before the test body's `runMapTest` returns; [closeNow] finishes it inside the body.
   */
  override fun close() {
    if (closed) return
    closed = true
    state.close()
    TestMapMain.deferCleanup { finishClose() }
  }

  /**
   * Closes the map, its runtime when the fixture owns it, and its driver, and waits for all three.
   */
  suspend fun closeNow() {
    if (closed) return
    closed = true
    state.close()
    finishClose()
  }

  private suspend fun finishClose() {
    var failure: Throwable? = null
    suspend fun cleanup(action: suspend () -> Unit) {
      try {
        action()
      } catch (error: Throwable) {
        val first = failure
        if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
      }
    }
    cleanup { state.awaitClosed() }
    if (ownsRuntime)
      cleanup {
        runtime.close()
        runtime.awaitClosed()
      }
    cleanup { scope.cancel() }
    cleanup { driver.close() }
    if (ownsRuntime) cleanup { FfiTestPlatform.deleteCacheFile(cacheFile) }
    failure?.let { throw it }
  }

  companion object {
    const val StyleLoaded: String = MapFixture.StyleLoaded

    /** About one display frame at 120 Hz. */
    private val FrameInterval = 8.milliseconds

    val DefaultExtent: MapExtent = MapFixture.DefaultExtent

    val RetinaExtent: MapExtent = MapFixture.RetinaExtent

    /**
     * Creates a fixture for the one native runtime packaged into this test process. Call it from a
     * `runMapTest` body, on [TestMapMain].
     */
    fun create(
      initialExtent: MapExtent = DefaultExtent,
      resourceConfig: MapResourceConfig = MapResourceConfig(),
      runtime: MapRuntime? = null,
      present: Boolean = true,
    ): BridgeMapFixture {
      FfiTestPlatform.initialize()
      val driver = FfiTestPlatform.createRenderDriver()
      val cacheFile = runtime?.nativeOwner?.options?.cacheFile ?: FfiTestPlatform.createCacheFile()
      return try {
        BridgeMapFixture(driver, cacheFile, initialExtent, resourceConfig, runtime, present)
      } catch (error: Throwable) {
        runCatching { driver.close() }
        if (runtime == null) FfiTestPlatform.deleteCacheFile(cacheFile)
        throw error
      }
    }
  }
}
