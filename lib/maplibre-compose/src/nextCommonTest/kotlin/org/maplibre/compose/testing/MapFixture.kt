package org.maplibre.compose.testing

import androidx.compose.ui.unit.DpOffset
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.map.GestureTarget
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Style
import org.maplibre.spatialk.geojson.Position

/**
 * A real map session on whichever MapLibre this platform uses, driven frame by frame by the test.
 * Every wait suspends: in the browser the map runs on the thread the test is on.
 */
internal interface MapFixture : AutoCloseable {

  val session: MapAdapter

  val gestures: GestureTarget

  val style: Style?

  val events: MutableList<String>

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
    condition: () -> Boolean,
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

    const val LOAD_FINISHED: String = "mapFinishedLoading"

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

internal class RecordingMapCallbacks : MapAdapter.Callbacks {

  val events: MutableList<String> = mutableListOf()

  val sourceChanges: MutableList<String?> = mutableListOf()

  val errors: MutableList<String> = mutableListOf()

  var style: Style? = null
    private set

  override fun onStyleChanged(map: MapAdapter, style: Style?) {
    this.style = style
    events += if (style == null) "styleChanged(null)" else MapFixture.STYLE_LOADED
  }

  override fun onMapFinishedLoading(map: MapAdapter) {
    events += MapFixture.LOAD_FINISHED
  }

  override fun onSourceChanged(map: MapAdapter, sourceId: String?) {
    sourceChanges += sourceId
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
