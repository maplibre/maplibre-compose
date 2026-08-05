package org.maplibre.compose.desktop

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import java.nio.file.Files
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
import org.maplibre.compose.map.DesktopMapSession
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Style
import org.maplibre.spatialk.geojson.Position

/**
 * Runs a real [DesktopMapSession] against [HeadlessVulkanMapHost], with no window and no Compose.
 *
 * This is the full desktop stack below the composables: a MapLibre runtime, a map, a style, a
 * render session on a real GPU, and frames driven by [pump]. Tests that assert on style JSON,
 * layers, or rendered-feature queries need all of it, because those only fail once MapLibre itself
 * is asked to do the work.
 *
 * Frames are driven explicitly rather than by a loop of their own: a test that says how many frames
 * it wants can fail with a clear message instead of hanging.
 */
internal class HeadlessMapFixture
private constructor(private val host: HeadlessVulkanMapHost, private val cacheDirectory: Path) :
  AutoCloseable {

  /** Everything the session reported back, in order, so tests can assert on lifecycle. */
  val events: MutableList<String> = mutableListOf()

  /** Errors the map reported. A non-empty list after a pump is a failure in almost every test. */
  val errors: MutableList<String> = mutableListOf()

  private var frameId = 0L
  private var frameRequested = true

  val session: DesktopMapSession =
    DesktopMapSession(
      callbacks = RecordingCallbacks(),
      logger = Logger.withTag("headless-map"),
      renderBackend = host.backends.producer,
      layoutDirection = LayoutDirection.Ltr,
      runtimeOptions =
        DesktopRuntimeOptions(
          cachePath = cacheDirectory.resolve("cache.db"),
          maximumCacheSizeBytes = null,
        ),
    )

  private val hostSession =
    object : DesktopMapHostSession {
      override val backends: DesktopBackendPair = host.backends

      override fun requestFrame() {
        frameRequested = true
      }

      override fun <T> withRendererAccess(action: () -> T): T = host.withRendererAccess(action)
    }

  init {
    session.onSurfaceAvailable(hostSession)
  }

  /**
   * Takes the surface away, as a host does when its device is lost.
   *
   * The host itself is deliberately left alone: what a sleep/wake cycle destroys is the render
   * session and the target it points at, not the map, its style, or its camera, and the point of
   * pairing this with [restoreSurface] is to prove that division holds.
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
   * Whether MapLibre has rendered at least once.
   *
   * The signal that the map exists and is attached, which a test needs before anything it does can
   * reach a map: the runtime and map are created on their own thread, so the first frame after
   * composition is not the one that renders.
   */
  var hasRendered: Boolean = false
    internal set

  /** Renders one frame, exactly as [DesktopMapSurface] does inside its draw pass. */
  fun frame(extent: DesktopMapExtent = DEFAULT_EXTENT): DesktopFrameResult {
    frameRequested = false
    val frame = host.acquireFrame(frameId++, extent, null)
    return try {
      host
        .withProducerAccess(frame) { session.render(frame) }
        .also {
          if (it == DesktopFrameResult.RENDERED) {
            host.completeProducerAccess(frame)
            hasRendered = true
          }
        }
    } finally {
      host.releaseFrame(frame)
    }
  }

  /** Renders frames until MapLibre has drawn once, so the map is known to exist. */
  fun pumpUntilRendered(extent: DesktopMapExtent = DEFAULT_EXTENT, timeout: Duration = 30.seconds) {
    pumpUntil("the map to render its first frame", timeout, extent) { hasRendered }
  }

  /**
   * Renders frames until [condition] holds, or fails.
   *
   * The runtime pumps itself on its own thread, but rendering is still the caller's job, and some
   * of what a test waits for needs it: mbgl advances a camera transition from
   * `onDidFinishRenderingFrame` while `transform.inTransition()`, so a transition that renders no
   * frames stalls after its first step.
   */
  fun pumpUntil(
    description: String,
    timeout: Duration = 30.seconds,
    extent: DesktopMapExtent = DEFAULT_EXTENT,
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
      // A short sleep rather than a spin: most of the wait is network and worker threads, and a
      // tight loop starves them on a small machine.
      Thread.sleep(POLL_INTERVAL_MILLIS)
    }
  }

  /**
   * Renders for [duration], but only when the session asks for a frame, and reports how many it
   * drew.
   *
   * This is the one loop shape that can measure whether a map is at rest, because it is the one a
   * real host uses: a desktop frame happens when [DesktopMapHostSession.requestFrame] invalidates
   * the surface, not on a clock. Drawing unconditionally instead — which is what a Compose UI
   * test's frame pump does — feeds the map frames it never asked for, and since a rendered frame is
   * itself something MapLibre can respond to, the loop then sustains itself and reads as a map that
   * will not settle. Counting frames under an unconditional pump measures the pump.
   */
  fun renderOnDemand(duration: Duration): Int {
    val deadline = TimeSource.Monotonic.markNow() + duration
    var rendered = 0
    while (deadline.hasNotPassedNow()) {
      if (frameRequested && frame() == DesktopFrameResult.RENDERED) rendered++
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
   * Anything that suspends on the map's progress needs both halves at once: the caller cannot block
   * the thread that renders and then wait for something that only advances when it does. A camera
   * animation is the case that bites — see [pumpUntil].
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
    extent: DesktopMapExtent = DEFAULT_EXTENT,
  ) {
    session.setBaseStyle(style)
    pumpUntil("style $style to load", timeout, extent) { events.contains(STYLE_LOADED) }
  }

  override fun close() {
    runCatching { session.close() }
    runCatching { host.close() }
    cacheDirectory.toFile().deleteRecursively()
  }

  private inner class RecordingCallbacks : MapAdapter.Callbacks {
    override fun onStyleChanged(map: MapAdapter, style: Style?) {
      this@HeadlessMapFixture.style = style
      events += if (style == null) "styleChanged(null)" else STYLE_LOADED
    }

    override fun onMapFinishedLoading(map: MapAdapter) {
      events += "mapFinishedLoading"
    }

    override fun onMapFailLoading(reason: String?) {
      errors += "mapFailLoading: $reason"
    }

    override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {
      // The reason is part of the event, not a detail: it is what tells a consumer the user did
      // this rather than the application, and it is only observable here.
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
    val DEFAULT_EXTENT: DesktopMapExtent =
      DesktopMapExtent.fromLogical(width = 512, height = 512, scaleFactor = 1.0)

    /** The same logical size at a different density, which forces the map to be rebuilt. */
    val RETINA_EXTENT: DesktopMapExtent =
      DesktopMapExtent.fromLogical(width = 512, height = 512, scaleFactor = 2.0)

    /**
     * Creates a fixture, failing if this machine has no usable Vulkan implementation.
     *
     * See [HeadlessVulkanMapHost.create] for why this fails rather than letting tests bail out: a
     * test that returns before asserting is recorded as passed, so an unusable machine would report
     * the same green suite as a working one.
     */
    fun create(): HeadlessMapFixture {
      val host = HeadlessVulkanMapHost.create()
      val directory = Files.createTempDirectory("maplibre-headless-test")
      return try {
        HeadlessMapFixture(host, directory)
      } catch (error: Throwable) {
        runCatching { host.close() }
        directory.toFile().deleteRecursively()
        throw error
      }
    }
  }
}
