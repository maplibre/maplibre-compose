package org.maplibre.compose.desktop

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
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
      override val capabilities: DesktopHostCapabilities = host.capabilities

      override fun requestFrame() {
        frameRequested = true
      }

      override fun <T> withRendererAccess(action: () -> T): T = host.withRendererAccess(action)
    }

  init {
    session.onSurfaceAvailable(hostSession)
  }

  /** Renders one frame, exactly as [DesktopMapSurface] does inside its draw pass. */
  fun frame(extent: DesktopMapExtent = DEFAULT_EXTENT): DesktopFrameResult {
    frameRequested = false
    val frame = host.acquireFrame(frameId++, extent, null)
    return try {
      host
        .withProducerAccess(frame) { session.render(frame) }
        .also { if (it == DesktopFrameResult.RENDERED) host.completeProducerAccess(frame) }
    } finally {
      host.releaseFrame(frame)
    }
  }

  /**
   * Renders frames until [condition] holds, or fails.
   *
   * MapLibre makes progress only while its runtime is pumped, and pumping only happens inside a
   * frame, so waiting for anything asynchronous — a style load, a tile, a camera transition — means
   * rendering frames at it.
   */
  fun pumpUntil(description: String, timeout: Duration = 30.seconds, condition: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    var frames = 0
    while (!condition()) {
      check(deadline.hasNotPassedNow()) {
        "Timed out after $frames frames waiting for $description. Errors: $errors"
      }
      frame()
      frames++
      // A short sleep rather than a spin: most of the wait is network and worker threads, and a
      // tight loop starves them on a small machine.
      Thread.sleep(POLL_INTERVAL_MILLIS)
    }
  }

  /** Renders a fixed number of frames, letting anything queued make progress. */
  fun pump(frames: Int = 30) {
    repeat(frames) {
      frame()
      Thread.sleep(POLL_INTERVAL_MILLIS)
    }
  }

  /** Applies a style and pumps until it finishes loading. */
  fun loadStyle(style: BaseStyle, timeout: Duration = 60.seconds) {
    session.setBaseStyle(style)
    pumpUntil("style $style to load", timeout) { events.contains(STYLE_LOADED) }
  }

  override fun close() {
    runCatching { session.close() }
    runCatching { host.close() }
    cacheDirectory.toFile().deleteRecursively()
  }

  private inner class RecordingCallbacks : MapAdapter.Callbacks {
    override fun onStyleChanged(map: MapAdapter, style: Style?) {
      events += if (style == null) "styleChanged(null)" else STYLE_LOADED
    }

    override fun onMapFinishedLoading(map: MapAdapter) {
      events += "mapFinishedLoading"
    }

    override fun onMapFailLoading(reason: String?) {
      errors += "mapFailLoading: $reason"
    }

    override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {
      events += "cameraMoveStarted"
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

    /**
     * Creates a fixture, or returns null when this machine has no usable Vulkan implementation.
     *
     * Tests skip on null. A runner without a GPU or a software driver says nothing about the code.
     */
    fun createOrNull(): HeadlessMapFixture? {
      val host = HeadlessVulkanMapHost.createOrNull() ?: return null
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
