package org.maplibre.compose.testing

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.unit.LayoutDirection
import js.date.Date
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.Promise
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import org.jetbrains.skiko.wasm.onWasmReady
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.gljs.DefaultWorkerUrl
import org.maplibre.compose.gljs.GlJsFrameTarget
import org.maplibre.compose.gljs.GlJsRuntime
import org.maplibre.compose.gljs.GlJsSurfaceSession
import org.maplibre.compose.gljs.jsNumberToDouble
import org.maplibre.compose.gljs.yieldToBrowser
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.GlJsMapSession
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.map.MapStateCallbacks
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleSnapshot
import web.html.HTMLCanvasElement

/** A [GlJsMapSession] on a canvas of its own, with no Compose or skiko, never composited. */
internal class GlJsMapFixture(private var extent: MapExtent) : MapFixture {

  private val recorder = RecordingMapCallbacks()
  private val runtime = mapRuntimeForTest()
  override val state =
    runtime.createMapState(
      cameraPosition = CameraPosition(zoom = 0.0),
      baseStyle = BaseStyle.Empty,
    )
  private val glJsSession = GlJsMapSession(state.lifecycle, recorder, MapLog, LayoutDirection.Ltr)
  private val token = state.reservePresentation()

  override val session: MapAdapter
    get() = glJsSession

  override val gestures: CameraInputTarget
    get() = glJsSession

  override val style: StyleBinding?
    get() = recorder.style

  override val events: RecordingList<String>
    get() = recorder.events

  override val engineEvents: RecordingList<MapEvent>
    get() = recorder.engineEvents

  override val errors: RecordingList<String>
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
      },
    )
    recorder.bindState(state)
    state.publishPresentation(token, glJsSession)
  }

  private fun frame(): Boolean {
    frameRequested = false
    val rendered = glJsSession.render(GlJsFrameTarget.OwnCanvas, extent)
    return rendered.also { if (it) hasRendered = true }
  }

  override suspend fun loadStyle(style: BaseStyle, timeout: Duration) {
    val styleLoadsBefore = events.count { it == MapFixture.StyleLoaded }
    checkNotNull(state.style.asMutable).baseStyle = style
    if (recorder.style?.isLoaded != true) {
      pumpUntil("style $style to load", timeout) {
        events.count { it == MapFixture.StyleLoaded } > styleLoadsBefore
      }
    }
    glJsSession.reconcileStyleRevision(StyleSnapshot.Empty)
    // Fixture loads wait for parsed style resources, independently of tile loading.
    MapStateCallbacks(state).onStyleReady(glJsSession)
    pumpUntil("the loaded style to be ready", timeout) {
      state.style.loadState == StyleLoadState.Ready
    }
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

  override fun resize(extent: MapExtent) {
    this.extent = extent
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
    val pixels =
      readCanvasPixel(canvas, x, canvas.height - 1 - y).toList().map {
        jsNumberToDouble(it).toInt()
      }
    return RgbaPixel(pixels[0], pixels[1], pixels[2], pixels[3])
  }

  override suspend fun <T> awaitWhileRendering(
    description: String,
    timeout: Duration,
    block: suspend () -> T,
  ): T = coroutineScope {
    val work = async { block() }
    pumpUntil(description, timeout) { work.isCompleted }
    work.await()
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
  // `pointAtWorker` keeps the first call. Pin the locally served worker here so a MapFixture
  // test that runs before `runBrowserMapTest` still keeps the suite off the CDN.
  GlJsRuntime.pointAtWorker(DefaultWorkerUrl)
  return GlJsMapFixture(extent)
}

internal actual suspend fun withTestMapRuntime(
  provider: MapResourceProvider?,
  block: suspend (MapRuntime) -> Unit,
) {
  val runtime = createMapRuntime { resourceProvider = provider }
  try {
    block(runtime)
  } finally {
    runtime.close()
    runtime.awaitClosed()
  }
}

internal actual val mapLibreFlavor: MapLibreFlavor = MapLibreFlavor.GlJs

/**
 * `Promise` without its parameter: an `expect class` cannot be actualized by a parameterized one.
 */
@JsName("Promise") external class JsPromise : JsAny

actual typealias MapTestResult = JsPromise

@OptIn(InternalComposeUiApi::class)
internal actual fun runMapTest(block: suspend CoroutineScope.() -> Unit): MapTestResult {
  GlJsRuntime.pointAtWorker(DefaultWorkerUrl)
  return MainScope()
    .async<JsAny?> {
      // Style declarations use a Skia graphics context, even on a map with its own WebGL canvas.
      Promise<JsAny?> { resolve, _ -> onWasmReady { resolve(null) } }.await()
      registerSkikoComposeImplementation()
      block()
      null
    }
    .asPromise()
    .unsafeCast<JsPromise>()
}

internal actual fun skipMapTest(reason: String): Nothing =
  error("Browser tests must run with the required capabilities: $reason")

private fun readCanvasPixel(canvas: HTMLCanvasElement, x: Int, y: Int): JsArray<JsNumber> =
  js(
    "{ const gl = canvas.getContext('webgl2'); const pixels = new Uint8Array(4); gl.readPixels(x,y,1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixels); return Array.from(pixels); }",
  )
