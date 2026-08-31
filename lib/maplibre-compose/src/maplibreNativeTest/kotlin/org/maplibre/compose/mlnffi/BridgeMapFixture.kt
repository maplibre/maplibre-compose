package org.maplibre.compose.mlnffi

import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MapPresentation
import org.maplibre.compose.map.MlnFfiMapSession
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.RecordingMapCallbacks
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.spatialk.geojson.Position

/**
 * Runs a real [MlnFfiMapSession] against the packaged runtime and production presentation bridge,
 * without a Compose composition. Frames are driven explicitly by the caller.
 */
internal class BridgeMapFixture
private constructor(
  private val driver: FfiTestRenderDriver,
  private val cacheFile: Path,
  private val initialExtent: MapExtent,
) : AutoCloseable {

  private val recorder = RecordingMapCallbacks()

  val events: MutableList<String>
    get() = recorder.events

  val sourceChanges: MutableList<String?>
    get() = recorder.sourceChanges

  val errors: MutableList<String>
    get() = recorder.errors

  val clickPositions: MutableList<Position>
    get() = recorder.clickPositions

  val longClickPositions: MutableList<Position>
    get() = recorder.longClickPositions

  /** The live style, once one has loaded. */
  val style: StyleBinding?
    get() = recorder.style

  private var frameId = 0L
  private var frameRequested = true
  private val runtime = mapRuntimeForTest()
  private val state = runtime.createMapState()

  val session: MlnFfiMapSession =
    MlnFfiMapSession(
      lifecycleAuthority = state.lifecycle,
      callbacks = recorder,
      logger = Logger.withTag("bridge-map"),
      renderBackend = driver.backends.producer,
      scaleFactor = initialExtent.scaleFactor,
      layoutDirection = LayoutDirection.Ltr,
      cacheFile = cacheFile,
    )

  fun bindPresentation(presentation: MapPresentation) {
    recorder.presentation = presentation
  }

  private val hostSession =
    object : MlnFfiMapHostSession {
      override val backends: RenderBackendPair = driver.backends

      override fun requestFrame() {
        frameRequested = true
      }

      override fun <T> withRendererAccess(action: () -> T): T = driver.withRendererAccess(action)

      override fun enqueueRenderer(action: () -> Unit): Boolean {
        driver.withRendererAccess(action)
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
  }

  /** Hands the surface back, and forgets that anything was ever rendered into the old one. */
  fun restoreSurface() {
    hasRendered = false
    session.onSurfaceAvailable(hostSession)
  }

  val attachCount: Int
    get() = session.attachCount

  /**
   * Whether MapLibre has rendered at least once, which is how a test knows the map exists and is
   * attached. The runtime and map are created on their own thread, so the first frame is not it.
   */
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
  fun readPixel(x: Int, y: Int): RgbaPixel = driver.readPixel(x, y)

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

  /** Runs [block] on another thread while this one renders frames, and returns its result. */
  fun <T> awaitWhileRendering(
    description: String,
    timeout: Duration = 30.seconds,
    block: suspend () -> T,
  ): T {
    val work = CoroutineScope(Dispatchers.Default).async { block() }
    pumpUntil(description, timeout) { work.isCompleted }
    return runBlocking { work.await() }
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

  companion object {
    const val STYLE_LOADED: String = MapFixture.STYLE_LOADED

    private const val POLL_INTERVAL_MILLIS = 8L

    val DEFAULT_EXTENT: MapExtent = MapFixture.DEFAULT_EXTENT

    val RETINA_EXTENT: MapExtent = MapFixture.RETINA_EXTENT

    /** Creates a fixture for the one native runtime packaged into this test process. */
    fun create(initialExtent: MapExtent = DEFAULT_EXTENT): BridgeMapFixture {
      FfiTestPlatform.initialize()
      val driver = FfiTestPlatform.createRenderDriver()
      val cacheFile = FfiTestPlatform.createCacheFile()
      return try {
        BridgeMapFixture(driver, cacheFile, initialExtent)
      } catch (error: Throwable) {
        runCatching { driver.close() }
        FfiTestPlatform.deleteCacheFile(cacheFile)
        throw error
      }
    }
  }
}
