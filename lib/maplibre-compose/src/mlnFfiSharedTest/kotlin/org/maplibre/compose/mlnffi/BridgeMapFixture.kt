package org.maplibre.compose.mlnffi

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MlnFfiMapSession
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Style
import org.maplibre.spatialk.geojson.Position

/**
 * Runs a real [MlnFfiMapSession] against the packaged runtime and production presentation bridge,
 * without a Compose composition.
 *
 * Frames are driven explicitly by the caller rather than by a loop of the fixture's own, so a test
 * that says how many frames it wants fails with a clear message instead of hanging.
 */
internal class BridgeMapFixture
private constructor(private val driver: FfiTestRenderDriver, private val cachePath: Path) :
  AutoCloseable {

  /** Everything the session reported back, in order, so tests can assert on lifecycle. */
  val events: MutableList<String> = mutableListOf()

  /** Errors the map reported. A non-empty list after a pump is a failure in almost every test. */
  val errors: MutableList<String> = mutableListOf()

  private var frameId = 0L
  private var frameRequested = true

  val session: MlnFfiMapSession =
    MlnFfiMapSession(
      callbacks = RecordingCallbacks(),
      logger = Logger.withTag("bridge-map"),
      renderBackend = driver.backends.producer,
      layoutDirection = LayoutDirection.Ltr,
      runtimeOptions = MlnFfiRuntimeOptions(cachePath = cachePath, maximumCacheSizeBytes = null),
    )

  private val hostSession =
    object : MlnFfiMapHostSession {
      override val backends: RenderBackendPair = driver.backends

      override fun requestFrame() {
        frameRequested = true
      }

      override fun <T> withRendererAccess(action: () -> T): T = driver.withRendererAccess(action)
    }

  init {
    session.onSurfaceAvailable(hostSession)
  }

  /**
   * Takes the surface away, as a host does when its device is lost. The host itself is deliberately
   * left alone: only the render session and its target go, not the map, its style, or its camera.
   */
  fun loseSurface() {
    session.onSurfaceLost()
  }

  /** Hands the surface back, and forgets that anything was ever rendered into the old one. */
  fun restoreSurface() {
    hasRendered = false
    session.onSurfaceAvailable(hostSession)
  }

  /** How many render sessions the map session has attached, for asserting a re-attach happened. */
  val attachCount: Int
    get() = session.attachCount

  /**
   * Whether MapLibre has rendered at least once, which is how a test knows the map exists and is
   * attached: the runtime and map are created on their own thread, so the first frame is not it.
   */
  var hasRendered: Boolean = false
    internal set

  /** Renders one frame, exactly as [MlnFfiMapSurface] does inside its draw pass. */
  fun frame(extent: MlnFfiMapExtent = DEFAULT_EXTENT): MlnFfiFrameResult {
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
  fun pumpUntilRendered(extent: MlnFfiMapExtent = DEFAULT_EXTENT, timeout: Duration = 30.seconds) {
    pumpUntil("the map to render its first frame", timeout, extent) { hasRendered }
  }

  /**
   * Renders frames until [condition] holds, or fails.
   *
   * Rendering is the caller's job: mbgl advances a camera transition from
   * `onDidFinishRenderingFrame`, so a transition that renders no frames stalls after its first
   * step.
   */
  fun pumpUntil(
    description: String,
    timeout: Duration = 30.seconds,
    extent: MlnFfiMapExtent = DEFAULT_EXTENT,
    condition: () -> Boolean,
  ) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    var frames = 0
    while (!condition()) {
      check(deadline.hasNotPassedNow()) {
        "Timed out after $frames frames waiting for $description. Errors: $errors"
      }
      frame(extent)
      frames++
      // A short sleep rather than a spin: a tight loop starves the network and worker threads.
      Thread.sleep(POLL_INTERVAL_MILLIS)
    }
  }

  /**
   * Renders for [duration], but only when the session asks for a frame, and reports how many it
   * drew.
   *
   * Only an on-demand loop can measure whether a map is at rest: a rendered frame is itself
   * something MapLibre can respond to, so an unconditional pump sustains and measures itself.
   */
  fun renderOnDemand(duration: Duration): Int {
    val deadline = TimeSource.Monotonic.markNow() + duration
    var rendered = 0
    while (deadline.hasNotPassedNow()) {
      if (frameRequested && frame() == MlnFfiFrameResult.RENDERED) rendered++
      Thread.sleep(POLL_INTERVAL_MILLIS)
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
      Thread.sleep(POLL_INTERVAL_MILLIS)
    }
  }

  /**
   * Runs [block] on another thread while this one renders frames, and returns its result.
   *
   * Anything that suspends on the map's progress needs both halves at once, since the caller cannot
   * block the rendering thread and then wait for something that only advances when it renders.
   */
  fun <T> awaitWhileRendering(
    description: String,
    timeout: Duration = 30.seconds,
    block: suspend () -> T,
  ): T {
    val work = CoroutineScope(Dispatchers.Default).async { block() }
    pumpUntil(description, timeout) { work.isCompleted }
    return runBlocking { work.await() }
  }

  /** The live style, once one has loaded. */
  var style: Style? = null
    private set

  /** Applies a style and pumps until it finishes loading. */
  fun loadStyle(
    style: BaseStyle,
    timeout: Duration = 60.seconds,
    extent: MlnFfiMapExtent = DEFAULT_EXTENT,
  ) {
    session.setBaseStyle(style)
    pumpUntil("style $style to load", timeout, extent) { events.contains(STYLE_LOADED) }
  }

  override fun close() {
    runCatching { session.close() }
    runCatching { driver.close() }
    FfiTestPlatform.deleteCachePath(cachePath)
  }

  private inner class RecordingCallbacks : MapAdapter.Callbacks {
    override fun onStyleChanged(map: MapAdapter, style: Style?) {
      this@BridgeMapFixture.style = style
      events += if (style == null) "styleChanged(null)" else STYLE_LOADED
    }

    override fun onMapFinishedLoading(map: MapAdapter) {
      events += "mapFinishedLoading"
    }

    override fun onMapFailLoading(reason: String?) {
      errors += "mapFailLoading: $reason"
    }

    override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {
      events += "cameraMoveStarted($reason)"
    }

    override fun onCameraMoved(map: MapAdapter) {
      events += "cameraMoved"
    }

    override fun onCameraMoveEnded(map: MapAdapter) {
      events += "cameraMoveEnded"
    }

    override fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset) {
      events += "click"
    }

    override fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset) {
      events += "longClick"
    }

    override fun onFrame(fps: Double) {}
  }

  companion object {
    const val STYLE_LOADED: String = "styleLoaded"

    private const val POLL_INTERVAL_MILLIS = 8L

    /** Big enough for tiles to be selected at zoom 0 and for a query to have something to hit. */
    val DEFAULT_EXTENT: MlnFfiMapExtent =
      MlnFfiMapExtent.fromLogical(width = 512, height = 512, scaleFactor = 1.0)

    /** The same logical size at a different density, which forces the map to be rebuilt. */
    val RETINA_EXTENT: MlnFfiMapExtent =
      MlnFfiMapExtent.fromLogical(width = 512, height = 512, scaleFactor = 2.0)

    /** Creates a fixture for the one native runtime packaged into this test process. */
    fun create(): BridgeMapFixture {
      FfiTestPlatform.initialize()
      val driver = FfiTestPlatform.createRenderDriver()
      val cachePath = FfiTestPlatform.createCachePath()
      return try {
        BridgeMapFixture(driver, cachePath)
      } catch (error: Throwable) {
        runCatching { driver.close() }
        FfiTestPlatform.deleteCachePath(cachePath)
        throw error
      }
    }
  }
}
