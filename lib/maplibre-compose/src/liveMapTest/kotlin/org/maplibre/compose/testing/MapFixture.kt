package org.maplibre.compose.testing

import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Deferred
import org.maplibre.compose.map.GestureTarget
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapAttachment
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding

/**
 * A real map session on whichever MapLibre this platform uses, driven frame by frame by the test.
 * Every wait suspends: in the browser the map runs on the thread the test is on.
 */
internal interface MapFixture : AutoCloseable {

  val session: MapAdapter

  /** Public logical-map surface exercised by style-handle tests. */
  val state: MapState

  val gestures: GestureTarget

  val style: StyleBinding?

  val events: MutableList<String>

  /** Every [MapEvent] the session posted through the adapter callbacks. */
  val engineEvents: MutableList<MapEvent>

  val sourceChanges: MutableList<String?>

  val errors: MutableList<String>

  suspend fun loadStyle(style: BaseStyle, timeout: Duration = 60.seconds)

  /** Renders until the map has drawn once. */
  suspend fun awaitMapReady(timeout: Duration = 30.seconds)

  suspend fun pump(frames: Int = 30)

  /** A camera transition and a tile load both advance from inside a render. */
  suspend fun pumpUntil(
    description: String,
    timeout: Duration = 30.seconds,
    condition: suspend () -> Boolean,
  )

  /**
   * Renders only the frames the map asks for, until it has asked for none across [quiet]. An
   * unconditional pump would sustain what it is measuring: a frame is itself something MapLibre
   * responds to.
   */
  suspend fun settle(quiet: Duration = 500.milliseconds, timeout: Duration = 30.seconds)

  /**
   * Renders one frame and reads a pixel of it, in physical pixels from the top left. One call
   * because the browser's drawing buffer is not promised to survive a turn of the event loop.
   */
  suspend fun readPixel(x: Int, y: Int): RgbaPixel

  suspend fun <T> awaitWhileRendering(
    description: String,
    timeout: Duration = 30.seconds,
    block: suspend () -> T,
  ): T

  /** Closes the session but not the fixture. */
  fun closeSession()

  companion object {
    const val STYLE_LOADED: String = "styleLoaded"

    const val STYLE_READY: String = "styleReady"

    /** Big enough for tiles to be selected at zoom 0 and for a query to have something to hit. */
    val DEFAULT_EXTENT: MapExtent =
      MapExtent.fromLogical(width = 512, height = 512, scaleFactor = 1.0)

    val RETINA_EXTENT: MapExtent =
      MapExtent.fromLogical(width = 512, height = 512, scaleFactor = 2.0)
  }
}

internal data class RgbaPixel(val red: Int, val green: Int, val blue: Int, val alpha: Int) {
  /** Two backends rasterize the same colour a channel or so apart. */
  fun isNear(other: RgbaPixel, tolerance: Int = 4): Boolean =
    abs(red - other.red) <= tolerance &&
      abs(green - other.green) <= tolerance &&
      abs(blue - other.blue) <= tolerance &&
      abs(alpha - other.alpha) <= tolerance
}

internal suspend fun MapFixture.pumpUntilPixel(
  description: String,
  x: Int,
  y: Int,
  expected: RgbaPixel,
  timeout: Duration = 30.seconds,
) {
  val deadline = TimeSource.Monotonic.markNow() + timeout
  var pixel = readPixel(x, y)
  while (!pixel.isNear(expected)) {
    check(deadline.hasNotPassedNow()) {
      "Timed out waiting for $description: ($x, $y) was $pixel, not $expected. Errors: $errors"
    }
    pump(frames = 1)
    pixel = readPixel(x, y)
  }
}

internal expect fun createMapFixture(extent: MapExtent = MapFixture.DEFAULT_EXTENT): MapFixture

internal enum class MapLibreFlavor {
  NATIVE,
  GL_JS,
}

internal expect val mapLibreFlavor: MapLibreFlavor

/** What a suspending test body hands back: `Unit` on the JVM, and a `Promise` in the browser. */
expect class MapTestResult

internal expect fun runMapTest(block: suspend () -> Unit): MapTestResult

internal class RecordingMapCallbacks(
  private val beforeStyleChanged: (MapAdapter, StyleBinding?) -> Unit = { _, _ -> }
) : MapAdapter.Callbacks {

  var attachment: MapAttachment? = null

  /** Set so the recorder exercises the real reactions rather than a copy of them. */
  var state: MapState? = null

  val events: MutableList<String> = RecordingList()

  val engineEvents: MutableList<MapEvent> = RecordingList()

  val sourceChanges: MutableList<String?> = RecordingList()

  val errors: MutableList<String> = RecordingList()

  var style: StyleBinding? = null
    private set

  override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
    beforeStyleChanged(map, style)
    this.style = style
    // The state learns of the binding here, as it does in composition, so that work the engine
    // starts on a freshly loaded style reaches it.
    state?.updateLoadedStyle(map, style)
    attachment?.updateViewport(map.getViewport())
    events += if (style == null) "styleChanged(null)" else MapFixture.STYLE_LOADED
  }

  override fun onStyleReady(map: MapAdapter) {
    events += MapFixture.STYLE_READY
  }

  override fun onStyleFailed(map: MapAdapter, reason: String?) {
    errors += "styleFailed: $reason"
  }

  override fun onStyleSourcesChanged(map: MapAdapter, sourceId: String?) {
    sourceChanges += sourceId
  }

  override fun onEvent(map: MapAdapter, event: MapEvent) {
    engineEvents += event
    state?.onEvent(map, event)
  }

  override fun resolveMissingImage(map: MapAdapter, imageId: String): Deferred<Unit>? =
    state?.resolveMissingImage(map, imageId)

  override fun onGestureActive(map: MapAdapter, active: Boolean) {
    events += "gesture($active)"
    state?.setGestureActive(map, active)
  }

  override fun onViewportChanged(map: MapAdapter) {
    events += "viewportChanged"
    state?.synchronizeCamera(map)
  }
}
