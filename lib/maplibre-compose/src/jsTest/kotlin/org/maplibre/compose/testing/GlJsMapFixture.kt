package org.maplibre.compose.testing

import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import kotlin.js.Date
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.promise
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.gljs.GlJsFrameTarget
import org.maplibre.compose.gljs.GlJsRuntime
import org.maplibre.compose.gljs.GlJsSurfaceSession
import org.maplibre.compose.gljs.LOCAL_WORKER_URL
import org.maplibre.compose.gljs.yieldToBrowser
import org.maplibre.compose.map.GestureTarget
import org.maplibre.compose.map.GlJsMapSession
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MapPresentation
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding

/** A [GlJsMapSession] on a canvas of its own, with no Compose or skiko, never composited. */
internal class GlJsMapFixture(private val extent: MapExtent) : MapFixture {

  private val recorder = RecordingMapCallbacks()
  private val runtime = mapRuntimeForTest()
  override val state =
    runtime.createMapState(
      initialCameraPosition = CameraPosition(zoom = 0.0),
      initialBaseStyle = BaseStyle.Empty,
    )
  private val glJsSession =
    GlJsMapSession(state.lifecycle, recorder, Logger.withTag("gljs-map"), LayoutDirection.Ltr)
  private val token = state.reservePresentation()

  override val session: MapAdapter
    get() = glJsSession

  override val presentation: MapPresentation
    get() = requireNotNull(state.presentation)

  override val gestures: GestureTarget
    get() = glJsSession

  override val style: StyleBinding?
    get() = recorder.style

  override val events: MutableList<String>
    get() = recorder.events

  override val sourceChanges: MutableList<String?>
    get() = recorder.sourceChanges

  override val errors: MutableList<String>
    get() = recorder.errors

  private var hasRendered = false
  private var frameRequested = true

  init {
    glJsSession.start()
    glJsSession.onSurfaceAvailable(
      object : GlJsSurfaceSession {
        override fun requestFrame() {
          frameRequested = true
        }
      }
    )
    state.publishPresentation(token, glJsSession)
    recorder.presentation = requireNotNull(state.presentation)
  }

  private fun frame(): Boolean {
    frameRequested = false
    val rendered = glJsSession.render(GlJsFrameTarget.Detached, extent)
    glJsSession.markPresentationStateReplayed()
    return rendered.also { if (it) hasRendered = true }
  }

  override suspend fun loadStyle(style: BaseStyle, timeout: Duration) {
    state.style.loadState = org.maplibre.compose.map.StyleLoadState.Loading
    state.updateLoadedStyle(glJsSession, null)
    val styleLoadsBefore = events.count { it == MapFixture.STYLE_LOADED }
    glJsSession.setBaseStyle(style)
    if (recorder.style?.isLoaded != true) {
      pumpUntil("style $style to load", timeout) {
        events.count { it == MapFixture.STYLE_LOADED } > styleLoadsBefore
      }
    }
    glJsSession.reconcileStyleRevision(DesiredStyleRevision.Empty)
    state.updateLoadedStyle(glJsSession, recorder.style)
    state.markStyleReady(glJsSession)
  }

  internal fun fireStyleError(message: String) {
    glJsSession.fireStyleErrorForTest(message)
  }

  internal fun detachPresentationForTest() {
    state.releasePresentation(token, glJsSession)
  }

  override suspend fun awaitMapReady(timeout: Duration) {
    pumpUntil("the map to render its first frame", timeout) { hasRendered }
  }

  override suspend fun pump(frames: Int) {
    repeat(frames) {
      frame()
      yieldToBrowser()
    }
  }

  internal fun renderFrameForTest(): Boolean = frame()

  override suspend fun pumpUntil(
    description: String,
    timeout: Duration,
    condition: suspend () -> Boolean,
  ) {
    val start = Date.now()
    var frames = 0
    while (!condition()) {
      check(Date.now() - start <= timeout.inWholeMilliseconds) {
        "Timed out after $frames frames waiting for $description. Errors: $errors"
      }
      frame()
      frames++
      // A real setTimeout, which is what lets MapLibre's promises, timers and fetches run.
      yieldToBrowser()
    }
  }

  override suspend fun settle(quiet: Duration, timeout: Duration) {
    val deadline = Date.now() + timeout.inWholeMilliseconds
    var quietSince = Date.now()
    while (Date.now() - quietSince < quiet.inWholeMilliseconds) {
      check(Date.now() <= deadline) {
        "Timed out waiting for the map to stop asking for frames. Errors: $errors"
      }
      if (frameRequested && frame()) quietSince = Date.now()
      yieldToBrowser()
    }
  }

  /** One task, no yield: MapLibre's own canvas has no `preserveDrawingBuffer`. */
  override suspend fun readPixel(x: Int, y: Int): RgbaPixel {
    frame()
    val canvas =
      checkNotNull(glJsSession.detachedCanvas()) { "the map has not built its canvas yet" }
    val gl = canvas.asDynamic().getContext("webgl2")
    val pixels = Uint8Array(4)
    // WebGL reads from the bottom left; every other coordinate on this fixture is top left.
    gl.readPixels(x, canvas.height - 1 - y, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixels)
    return RgbaPixel(pixels[0].toInt(), pixels[1].toInt(), pixels[2].toInt(), pixels[3].toInt())
  }

  override suspend fun <T> awaitWhileRendering(
    description: String,
    timeout: Duration,
    block: suspend () -> T,
  ): T {
    val work = CoroutineScope(Dispatchers.Default).async { block() }
    pumpUntil(description, timeout) { work.isCompleted }
    return work.await()
  }

  override fun closeSession() {
    state.close()
  }

  override fun close() {
    // Every map holds a WebGL context, and browsers cap how many may live at once.
    state.close()
    runtime.close()
  }
}

internal actual fun createMapFixture(extent: MapExtent): MapFixture {
  // `pointAtWorker` keeps the first call. Pin the Karma-served worker here so a MapFixture
  // test that runs before `runBrowserMapTest` still keeps the suite off the CDN.
  GlJsRuntime.pointAtWorker(LOCAL_WORKER_URL)
  return GlJsMapFixture(extent)
}

internal actual val mapLibreFlavor: MapLibreFlavor = MapLibreFlavor.GL_JS

/**
 * `Promise` without its parameter: an `expect class` cannot be actualized by a parameterized one.
 */
@JsName("Promise") external class JsPromise

actual typealias MapTestResult = JsPromise

internal actual fun runMapTest(block: suspend () -> Unit): MapTestResult {
  GlJsRuntime.pointAtWorker(LOCAL_WORKER_URL)
  return MainScope().promise { block() }.unsafeCast<JsPromise>()
}
