package org.maplibre.compose.map

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import js.objects.unsafeJso
import kotlin.coroutines.resume
import kotlin.js.Promise
import kotlin.math.log2
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraAnchor
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.camera.CubicBezier
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.camera.internal.BoxZoomFit
import org.maplibre.compose.camera.internal.CameraCommandGuard
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.camera.internal.runCameraCommand
import org.maplibre.compose.camera.resolveScreenPoint
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.gljs.CameraForBoundsOptions
import org.maplibre.compose.gljs.DefaultWorkerUrl
import org.maplibre.compose.gljs.EaseToOptions
import org.maplibre.compose.gljs.FilterSpecification
import org.maplibre.compose.gljs.FlyToOptions
import org.maplibre.compose.gljs.GlJsFrameTarget
import org.maplibre.compose.gljs.GlJsMapEvent
import org.maplibre.compose.gljs.GlJsMapRenderer
import org.maplibre.compose.gljs.GlJsRenderTarget
import org.maplibre.compose.gljs.GlJsRuntime
import org.maplibre.compose.gljs.GlJsSubscription
import org.maplibre.compose.gljs.GlJsSurfaceSession
import org.maplibre.compose.gljs.GlJsTerrain
import org.maplibre.compose.gljs.GlJsTransform
import org.maplibre.compose.gljs.JumpToOptions
import org.maplibre.compose.gljs.LngLat
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.PaddedCameraOptions
import org.maplibre.compose.gljs.PaddingOptions
import org.maplibre.compose.gljs.Point
import org.maplibre.compose.gljs.QueryRenderedFeaturesOptions
import org.maplibre.compose.gljs.isPointOnMapSurface
import org.maplibre.compose.gljs.queryBox
import org.maplibre.compose.gljs.queryPoint
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.interaction.BearingSnapping
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogSource
import org.maplibre.compose.resource.GlJsRequestController
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.style.StyleLoadTracker
import org.maplibre.compose.style.StylePresentation
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.StyleRequestId
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.util.AngleMath
import org.maplibre.compose.util.DelicateMaplibreComposeApi
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.metersPerDpAtLatitude
import org.maplibre.compose.util.positions
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toCameraPadding
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toDpPadding
import org.maplibre.compose.util.toGeoJsonFeature
import org.maplibre.compose.util.toJsValue
import org.maplibre.compose.util.toLngLat
import org.maplibre.compose.util.toLngLatBounds
import org.maplibre.compose.util.toPaddingOptions
import org.maplibre.compose.util.toPoint
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toStyleJson
import org.maplibre.compose.util.toVisibleBounds
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import web.dom.document
import web.gl.WebGL2RenderingContext
import web.html.HTMLCanvasElement
import web.html.HTMLElement

/**
 * Creates the engine when a host supplies its first render target and extent, replaying the desired
 * state. Reads before then answer from what was last asked for. A DOM host supplies [mapContainer];
 * Compose hosts use an offscreen container and borrow Compose's WebGL context.
 */
internal class GlJsMapSession(
  private val lifecycleAuthority: MapLifecycleAuthority,
  callbacks: MapAdapter.Callbacks,
  internal var logger: MapLog?,
  internal var layoutDirection: LayoutDirection,
  private val requests: GlJsRequestController? = null,
  internal var mapContainer: HTMLElement? = null,
) : MapAdapter, GlJsMapRenderer, CameraInputTarget {

  init {
    createdCount += 1
  }

  internal var callbacks: MapAdapter.Callbacks = callbacks
  private var closing = false
  private var presenting = false
  private var closeResult: Result<Unit> = Result.success(Unit)

  /** The pending base-style load, then the loaded style's data listeners. */
  private val styleSubscriptions = mutableListOf<GlJsSubscription>()

  private data class EngineState(val map: MaplibreMap? = null, val ended: Boolean = false)

  private val engineState = MutableStateFlow(EngineState())
  private var map: MaplibreMap?
    get() = engineState.value.map
    set(value) {
      engineState.value = engineState.value.copy(map = value)
    }

  private val mapSubscriptions = mutableListOf<GlJsSubscription>()
  private val listenerCalls = ArrayDeque<() -> Unit>()
  private var engineCallDepth = 0
  private var deliveringListeners = false

  /** MapLibre sizes its viewport from a container even when it renders nowhere near one. */
  private var container: HTMLElement? = null

  private var surface: GlJsSurfaceSession? = null

  private val cameraTransitions = GlJsCameraTransitions()

  /** Whether the current engine map has loaded its first base style. */
  private var hasLoadedInitialStyle = false

  /** True after the current engine map has applied a non-empty presentation viewport. */
  internal var hasUsableViewport by mutableStateOf(false)
    private set

  /** Whether the current engine map may be shown by its host. */
  internal val canPresentFrames: Boolean
    get() = styleLoadTracker.presentation != StylePresentation.Hidden

  private var requestedStyle: BaseStyle? = null
  private val styleLoadTracker = StyleLoadTracker()
  private var appliedStyleRequest: StyleRequestId? = null

  /** Set while a style load is outstanding and its listener classifies `error` events. */
  private var styleLoadPending = false

  private var styleBinding: GlJsStyleBinding? = null
  private val styleReconciler = StyleReconciler()
  private var appliedExtent: MapExtent = MapExtent.Empty

  private var framebuffer: Any? = null

  private var lentContext: WebGL2RenderingContext? = null

  override var maximumFps: Int? = null
    private set

  private var cameraConstraints: CameraConstraints? = null
  private var renderSettings = RenderOptions.Standard
  private var tileLodOptions: TileLodOptions = TileLodOptions.Standard
  private var renderedProjection by mutableStateOf<RenderedProjection?>(null)

  private data class PresentedGeometry(val target: GlJsRenderTarget?, val extent: MapExtent)

  private var presentedGeometry by mutableStateOf<PresentedGeometry?>(null)

  override fun presentFrame(target: GlJsRenderTarget?, extent: MapExtent) {
    presentedGeometry = PresentedGeometry(target, extent)
  }

  private class RenderedProjection(
    val target: GlJsRenderTarget?,
    val extent: MapExtent,
    val transform: GlJsTransform,
    val terrain: GlJsTerrain?,
  ) {
    val locations = mutableMapOf<Position, DpOffset>()
    var terrainChanged = false
  }

  // region surface lifecycle

  override fun onSurfaceAvailable(surface: GlJsSurfaceSession) {
    if (closing) return
    this.surface = surface
    surface.requestFrame()
  }

  override fun onSurfaceLost(surface: GlJsSurfaceSession) {
    if (this.surface !== surface) return
    // The map's context belongs to the surface, so it cannot outlive it.
    if (map != null) {
      // The replacement keeps the presentation, and the destroyed map emits no `moveend`. A
      // gesture that ends while the replacement is attaching cannot report, so its fact is
      // withdrawn here; a gesture that continues re-reports on its next camera command.
      activeGestureToken = null
      reportGestureActive(false)
      lifecycleAuthority.endCurrentPresentationCameraChange(this)
      this.surface = null
      invalidateStyleBinding()
      destroyMap()
    } else {
      this.surface = null
    }
  }

  override fun render(target: GlJsFrameTarget, extent: MapExtent): Boolean = engineCall {
    renderFrame(target, extent)
  }

  private fun renderFrame(target: GlJsFrameTarget, extent: MapExtent): Boolean {
    if (closing || extent.isEmpty) return false
    if (target is GlJsFrameTarget.UnsupportedSize) return false
    if (target is GlJsFrameTarget.Composited && map != null && lentContext !== target.target.gl) {
      // A new Skia handle can still share our WebGL context. Only a different WebGL context
      // requires replacing the engine and replaying the presentation's state.
      val host = surface ?: return false
      onSurfaceLost(host)
      onSurfaceAvailable(host)
      return false
    }
    if (styleLoadTracker.presentation == StylePresentation.Retained) return false
    // A map with its own canvas cannot later adopt a borrowed context: everything it uploaded
    // belongs to the context it already has.
    val composited = target as? GlJsFrameTarget.Composited
    if (target is GlJsFrameTarget.NotReady && map == null) return false
    framebuffer = composited?.target?.framebuffer
    val map = ensureMap(composited?.target, extent) ?: return false
    if (composited == null) {
      applyExtent(map, extent)
    } else {
      val mapTarget = composited.target
      GlJsRuntime.withDrawingBufferSize(mapTarget.gl, mapTarget.widthPx, mapTarget.heightPx) {
        applyExtent(map, extent)
      }
    }
    if (target is GlJsFrameTarget.NotReady) return false

    val previous = renderedProjection
    if (composited != null) {
      // Skia drives this context between MapLibre's frames, so each renderer is told the other
      // moved the state.
      val mapTarget = composited.target
      try {
        mapTarget.prepareMapRender()
        map.painter.context.setDirty()
        GlJsRuntime.withDrawingBufferSize(mapTarget.gl, mapTarget.widthPx, mapTarget.heightPx) {
          map.redraw()
        }
      } finally {
        mapTarget.resetSkiaState()
      }
    } else {
      // GL JS runs style updates, tile loading and every camera ease from inside its own render, so
      // even a map nothing samples has to be asked to draw.
      map.redraw()
    }

    renderedProjection =
      RenderedProjection(composited?.target, extent, map._camera.transform.clone(), map.terrain)

    if (previous == null) {
      logger?.i {
        "Rendered the first map frame at ${extent.physicalWidth}x${extent.physicalHeight}"
      }
    }
    return true
  }

  internal fun detachedCanvas(): HTMLCanvasElement? =
    if (lentContext != null) null else map?.getCanvas()

  /**
   * Destroys the map, releases camera transitions, and reports closure to [lifecycleAuthority].
   * Each cleanup step runs even when an earlier one fails.
   */
  override fun close() {
    if (closing) return
    closing = true
    val failures = mutableListOf<Throwable>()
    fun attempt(cleanup: () -> Unit) {
      runCatching(cleanup).exceptionOrNull()?.let(failures::add)
    }
    presenting = false
    attempt { destroyMap() }
    attempt {
      activeGestureToken = null
      surface = null
    }
    attempt { cameraTransitions.releaseAll() }
    closeResult = failures.cleanupResult("Map")
    engineState.value = EngineState(ended = true)
    lifecycleAuthority.sessionClosing(this)
  }

  override suspend fun awaitClosed() {
    closeResult.getOrThrow()
  }

  /** Releases the presentation's context while keeping the logical adapter. */
  override suspend fun detachPresentation() {
    presenting = false
    engineState.value = engineState.value.copy(ended = true)
    destroyMap()
    cameraTransitions.releaseAll()
    activeGestureToken = null
    surface = null
    lifecycleAuthority.mapUnavailable()
  }

  /** Adopts this session and begins its presentation. The map itself waits for the first frame. */
  fun start() {
    if (!lifecycleAuthority.adopt(this) || presenting) return
    presenting = true
    engineState.value = EngineState()
    surface?.requestFrame()
  }

  /**
   * MapLibre takes its WebGL context and its size at construction, so the map cannot exist before
   * the first frame that has somewhere to draw. A null [target] builds a detached map, which takes
   * a context from its own canvas.
   */
  private fun ensureMap(target: GlJsRenderTarget?, extent: MapExtent): MaplibreMap? {
    map?.let {
      return it
    }
    if (closing) return null
    if (!lifecycleAuthority.selectAdapterForPresentation(this)) return null
    if (!presenting) return null

    val host =
      mapContainer
        ?: document.createElement("div").unsafeCast<HTMLElement>().also {
          it.style.cssText = OffscreenContainerStyle
          document.body.appendChild(it)
        }
    host.style.width = "${extent.width}px"
    host.style.height = "${extent.height}px"
    container = host

    // The map takes no input of its own; gestures arrive through CameraInputTarget below.
    val options =
      headlessMapOptions(host, extent.scaleFactor, requests) {
        // Both hosts apply extents and schedule frames themselves. GL JS's ResizeObserver also
        // calls redraw(), bypassing activation, frame limits, and retained-style presentation.
        trackResize = false
        target?.let {
          // MapLibre otherwise clamps its pixel ratio to the drawing buffer of the canvas it
          // shares, which is Compose's whole viewport at the moment the map was built.
          maxCanvasSize = maxTextureSize(it.gl)
        }
      }
    GlJsRuntime.pointAtWorker(DefaultWorkerUrl)
    val created =
      if (target == null) MaplibreMap(options)
      else {
        val context = target.gl.unsafeCast<WebGL2RenderingContext>()
        lentContext = context
        GlJsRuntime.withDrawingBufferSize(context, target.widthPx, target.heightPx) {
          GlJsRuntime.lendingContext(context) { MaplibreMap(options) }
        }
      }

    // Before the style resolves: any render before the redirect lands on Compose's canvas.
    if (target != null) {
      GlJsRuntime.redirectDefaultFramebuffer(created.painter.context) { framebuffer }
    }
    GlJsRuntime.interceptRepaintRequests(created) { surface?.requestFrame() }
    wireEvents(created)

    hasLoadedInitialStyle = false
    appliedExtent = MapExtent.Empty
    cameraConstraints?.let { applyCameraConstraints(created, it) }
    requestedCamera?.let { created.jumpTo(it.toJumpToOptions()) }
    applyRenderSettings(created)
    applyRequestedStyle(created)
    map = created
    return created.takeIf { !closing && map === created }
  }

  private fun destroyMap() {
    hasLoadedInitialStyle = false
    val current = map ?: return
    requestedCamera = current.readCameraPosition(viewportInsets)
    mapSubscriptions.forEach { it.cancel() }
    mapSubscriptions.clear()
    map = null
    hasUsableViewport = false
    invalidateStyleBinding()
    current.setMissingStyleImageResolver(null)
    cancelStyleSubscriptions()
    appliedStyleRequest = null
    styleLoadPending = false
    styleLoadTracker.engineBecameUnavailable()
    renderedProjection = null
    presentedGeometry = null
    val borrowed = lentContext
    lentContext = null
    runCatching {
      if (borrowed == null) current.remove()
      else GlJsRuntime.removingWithoutLosingContext(borrowed) { current.remove() }
    }
      .onFailure { logger?.e(it) { "MapLibre failed to close" } }
    if (mapContainer == null) container?.let { runCatching { it.remove() } }
    container = null
    cameraTransitions.engineDestroyed()
  }

  private fun cancelStyleSubscriptions() {
    styleSubscriptions.forEach { it.cancel() }
    styleSubscriptions.clear()
  }

  private fun invalidateStyleBinding() {
    lifecycleAuthority.postToMain { callbacks.onStyleChanged(this, null) }
    styleBinding?.invalidate()
    styleBinding = null
  }

  private fun applyExtent(map: MaplibreMap, extent: MapExtent) {
    if (extent == appliedExtent) return
    if (appliedExtent.width != extent.width || appliedExtent.height != extent.height)
      engineCall { cameraTransitions.cancelAnchor(map) }
    appliedExtent = extent
    container?.let { host ->
      host.style.width = "${extent.width}px"
      host.style.height = "${extent.height}px"
    }
    map.setPixelRatio(extent.scaleFactor)
    map.resize()
    hasUsableViewport = true
    // Tell overlays about viewport changes even when resize() did not move the camera.
    // The first resize can precede presentation publication, so seed its viewport here too.
    lifecycleAuthority.seedCurrentPresentationViewport(this)
    if (presenting) callbacks.onViewportChanged(this)
  }

  /** The current GL JS engine-map instance, exposed only to browser boundary tests. */
  internal fun engineMapForTest(): MaplibreMap? = map

  @OptIn(DelicateMaplibreComposeApi::class, ExperimentalMaplibreComposeApi::class)
  internal suspend fun <T> withPlatformMap(block: PlatformMapScope.() -> T): T {
    val current =
      engineState
        .first {
          if (closing || !presenting || it.ended)
            throw CancellationException("The Web platform map changed before access could begin")
          it.map != null
        }
        .map!!
    return engineCall { PlatformMapScope(current).block() }
  }

  private fun maxTextureSize(gl: dynamic): Array<Double> {
    val size = (gl.getParameter(gl.MAX_TEXTURE_SIZE) as? Int)?.toDouble() ?: 4096.0
    return arrayOf(size, size)
  }

  // endregion

  // region events

  private fun wireEvents(map: MaplibreMap) {
    // Terrain's samplers retain mutable DEM data. Once data or terrain changes, only locations
    // already sampled for the retained image are safe to use until another frame is rendered.
    for (event in listOf("data", "terrain")) {
      mapSubscriptions +=
        listen(map, event) {
          renderedProjection?.takeIf { it.terrain != null }?.terrainChanged = true
        }
    }
    mapSubscriptions +=
      listen(map, "error") { event ->
        val reason = event.error?.message ?: "MapLibre failed to load the map"
        if (!styleLoadPending) {
          // Tile and sprite failures land here too, and are not the map failing to load. Listening
          // is what silences the browser's own console.error fallback, so the record reaches the
          // logger only through this listener.
          logger?.log(
            MapLogLevel.Error,
            throwable = null,
            message = { reason },
            source = MapLogSource.WebEngine,
            category = event.sourceId ?: event.asDynamic().layer?.id as? String,
          )
        }
      }

    // MapLibre awaits this before it treats the image as missing, so a resolved image satisfies
    // the request that asked for it rather than only later ones.
    map.setMissingStyleImageResolver { imageId ->
      val binding = styleBinding?.takeIf { it.isLoaded }
      binding?.let {
        Promise<Unit> { resolve, _ ->
          deliverListener {
            val resolution =
              if (styleBinding === binding && binding.isLoaded)
                callbacks.resolveMissingImage(this, imageId)
              else null
            if (resolution == null) resolve(Unit)
            else resolution.invokeOnCompletion { resolve(Unit) }
          }
        }
      }
    }

    subscribeTranslated(map, EngineGlJsEvents)
    subscribeTranslated(map, PresentationGlJsEvents)
  }

  private fun subscribeTranslated(
    map: MaplibreMap,
    translations: Map<String, GlJsEventTranslation>,
  ) {
    for ((type, translate) in translations) {
      var active = true
      val subscription =
        map.subscribe(type) { raw ->
          val event = translate(raw)
          // Capture the ended movement before another engine call registers a waiter.
          val deliver =
            if (event is MapEvent.CameraMoveEnded) {
              cameraTransitions.movementEnded { callbacks.onEvent(this, event) }
            } else {
              { callbacks.onEvent(this, event) }
            }
          deliverListener {
            if (active) {
              requestedCamera = map.readCameraPosition(viewportInsets)
              deliver()
            }
          }
        }
      mapSubscriptions += GlJsSubscription {
        active = false
        subscription.cancel()
      }
    }
  }

  private fun reportBaseStyleReady(binding: GlJsStyleBinding) {
    if (map?.isStyleLoaded() == true && styleLoadTracker.baseStyleReady(binding.identity)) {
      try {
        callbacks.onStyleReady(this)
      } catch (error: Throwable) {
        styleLoadTracker.failed(binding.identity)
        throw error
      }
    }
  }

  // endregion

  // region dispatch

  /** Delivers listeners after the outermost engine call, including calls made by a listener. */
  private inline fun <T> engineCall(action: () -> T): T {
    engineCallDepth++
    try {
      return action()
    } finally {
      engineCallDepth--
      drainListeners()
    }
  }

  private fun deliverListener(action: () -> Unit) {
    listenerCalls.addLast(action)
    drainListeners()
  }

  private fun drainListeners() {
    if (engineCallDepth != 0 || deliveringListeners) return
    deliveringListeners = true
    try {
      while (listenerCalls.isNotEmpty()) listenerCalls.removeFirst()()
    } finally {
      deliveringListeners = false
    }
  }

  private fun listen(
    map: MaplibreMap,
    type: String,
    action: (GlJsMapEvent) -> Unit,
  ): GlJsSubscription {
    var active = true
    val subscription =
      map.subscribe(type) { event ->
        deliverListener { if (active) action(event) }
      }
    return GlJsSubscription {
      active = false
      subscription.cancel()
    }
  }

  private fun onMap(action: (MaplibreMap) -> Unit) {
    if (!closing) map?.let { engineCall { action(it) } }
  }

  private fun <T> withMap(fallback: T, action: (MaplibreMap) -> T): T =
    map?.let { engineCall { action(it) } } ?: fallback

  // endregion

  // region MapAdapter

  override fun setBaseStyle(style: BaseStyle) {
    if (style == requestedStyle) return
    // Must precede the new style: the old style's sources and layers would otherwise recompose
    // against base layers being replaced.
    styleBinding?.invalidate()
    requestedStyle = style
    styleLoadTracker.request()
    callbacks.onStyleChanged(this, null)
    onMap(::applyRequestedStyle)
    if (map == null) lifecycleAuthority.mapUnavailable()
  }

  override suspend fun reconcileStyleRevision(revision: StyleSnapshot): Boolean {
    val binding = checkNotNull(styleBinding)
    try {
      engineCall { styleReconciler.apply(binding, revision) }
      val rejected = styleReconciler.settle(binding)
      if (styleLoadTracker.reconciled(binding.identity)) {
        callbacks.onStyleReady(this)
      }
      surface?.requestFrame()
      return rejected
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      styleLoadTracker.failed(binding.identity)
      throw error
    }
  }

  private fun applyRequestedStyle(map: MaplibreMap) {
    val style = requestedStyle ?: return
    if (styleLoadPending) return
    val trackerRequest = styleLoadTracker.requestId
    if (appliedStyleRequest == trackerRequest) return
    appliedStyleRequest = trackerRequest
    styleLoadPending = true
    cancelStyleSubscriptions()
    try {
      styleSubscriptions +=
        map.loadBaseStyle(
          style,
          onLoaded = {
            deliverListener onLoaded@{
              if (this.map !== map) return@onLoaded
              styleLoadPending = false
              val binding =
                GlJsStyleBinding(map, logger, callEngine = { engineCall(it) }) {
                  appliedExtent.scaleFactor.toFloat()
                }
              if (
                !styleLoadTracker.loaded(
                  trackerRequest,
                  binding.identity,
                  map.isStyleLoaded(),
                )
              ) {
                binding.invalidate()
                applyRequestedStyle(map)
                return@onLoaded
              }
              if (!closing) {
                styleBinding?.invalidate()
                styleBinding = binding
                callbacks.onStyleChanged(this, binding)
                callbacks.onEvent(this, MapEvent.StyleLoaded)
                styleSubscriptions += listen(map, "styledata") { reportBaseStyleReady(binding) }
                styleSubscriptions +=
                  listen(map, "sourcedata") { event ->
                    if (event.sourceDataType == "metadata") {
                      applyTileLod(map)
                      event.sourceId?.let { callbacks.onStyleSourcesChanged(this, it) }
                    }
                    reportBaseStyleReady(binding)
                  }
                applyTileLod(map)
                if (!hasLoadedInitialStyle) {
                  hasLoadedInitialStyle = true
                  cameraTransitions.initialStyleLoaded(map)
                }
              } else {
                binding.invalidate()
              }
            }
          },
          onFailed = { message ->
            deliverListener {
              if (this.map !== map) return@deliverListener
              val reason = message ?: "MapLibre failed to load the map"
              styleLoadPending = false
              val accepted = styleLoadTracker.failed(trackerRequest)
              if (accepted) {
                if (!closing) {
                  callbacks.onStyleFailed(this, reason)
                  logger?.e { "Map loading failed: $reason" }
                  if (!hasLoadedInitialStyle) cameraTransitions.releasePending()
                  callbacks.onEvent(this, MapEvent.StyleLoadFailed(reason))
                }
              } else {
                applyRequestedStyle(map)
              }
            }
          },
        )
    } catch (error: Throwable) {
      styleLoadPending = false
      val reason = error.message ?: "MapLibre failed to load the map"
      logger?.e(error) { "Map loading failed: $reason" }
      if (styleLoadTracker.failed(trackerRequest)) {
        callbacks.onStyleFailed(this, reason)
        callbacks.onEvent(this, MapEvent.StyleLoadFailed(reason))
      }
      if (!hasLoadedInitialStyle) cameraTransitions.releasePending()
    }
  }

  internal fun fireStyleErrorForTest(message: String) {
    val currentMap = map ?: return
    val properties = js("({})")
    properties.error = js("new Error()")
    properties.error.message = message
    properties.style = currentMap.asDynamic().style
    properties.sourceId = "unrelated-source"
    currentMap.fire("error", properties)
  }

  /** Answers camera reads made before the map exists. */
  private var requestedCamera: CameraPosition? = null
  private val insets = ViewportInsets()
  private val viewportInsets: PaddingOptions
    get() = insets.applied.toPaddingOptions()

  override fun getCameraPosition(): CameraPosition =
    withMap(requestedCamera ?: CameraPosition()) { map -> map.readCameraPosition(viewportInsets) }

  override fun setCameraPosition(cameraPosition: CameraPosition, guard: CameraCommandGuard?) {
    if (guard?.isValid() == false) return
    requestedCamera = cameraPosition
    cameraTransitions.releasePending()
    onMap { map -> if (guard?.isValid() != false) map.jumpTo(cameraPosition.toJumpToOptions()) }
  }

  override fun setViewportInsets(insets: PaddingValues) {
    if (!this.insets.set(insets.toDpPadding(layoutDirection))) return
    val padding =
      this.insets.apply(
        beforeChange = { engineCall { cameraTransitions.cancelAnchor(map) } },
        currentCamera = ::getCameraPosition,
      ) ?: return
    onMap { map ->
      map.jumpTo(unsafeJso<JumpToOptions> { this.padding = padding.toPaddingOptions() })
    }
  }

  override suspend fun cameraForBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition =
    checkNotNull(
      map?.cameraPositionForBounds(boundingBox, bearing, pitch, cameraPadding, fitPadding),
    ) {
      "The map could not calculate a camera for the bounds"
    }

  override suspend fun cameraForGeometry(
    geometry: Geometry,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition =
    withMap(null as CameraPosition?) { map ->
      map.cameraPositionForPositions(
        geometry.positions(),
        bearing,
        pitch,
        cameraPadding,
        fitPadding,
      )
    } ?: throw IllegalStateException("The map could not calculate a camera for the geometry")

  override suspend fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    guard: CameraCommandGuard?,
  ) {
    if (guard?.isValid() == false) return
    cameraTransitions.releasePending()
    onMap { map ->
      if (guard?.isValid() == false) return@onMap
      map.cameraPositionForBounds(boundingBox, bearing, pitch, cameraPadding, fitPadding)?.let {
        map.jumpTo(it.toJumpToOptions())
      }
    }
  }

  override suspend fun animateCamera(
    update: CameraUpdate,
    animation: CameraAnimation,
    guard: CameraCommandGuard?,
  ) {
    awaitCameraRelease(guard = guard) { map -> map.animateTo(update, animation) }
  }

  override suspend fun animateCameraAround(
    anchor: CameraAnchor,
    zoom: Double?,
    bearing: Double?,
    pitch: Double?,
    animation: CameraAnimation.Ease,
    guard: CameraCommandGuard?,
  ) {
    awaitCameraRelease(guard = guard, anchored = true) { map ->
      val extent = appliedExtent
      val point =
        anchor.resolveScreenPoint(
          androidx.compose.ui.unit.DpSize(extent.width.dp, extent.height.dp),
          centerLongitude = map.getCenter().lng,
          project = { position ->
            val longitude =
              with(AngleMath) { map.getCenter().lng + position.longitude.diff(map.getCenter().lng) }
            map.project(LngLat(lng = longitude, lat = position.latitude)).toDpOffset()
          },
          unproject = { map.unprojectAt(it.x.value.toDouble(), it.y.value.toDouble()) },
        )
      // A behind-camera intersection can round-trip through project/unproject. Ask the engine
      // whether this screen point is on the map, independently of its visible world copy.
      require(map.isPointOnMapSurface(point.toPoint())) { "The anchor must project onto the map" }
      map.easeTo(
        unsafeJso<EaseToOptions> {
          around = map.unprojectAt(point.x.value.toDouble(), point.y.value.toDouble()).toLngLat()
          zoom?.let { this.zoom = it }
          bearing?.let { this.bearing = it }
          pitch?.let { this.pitch = it }
          duration = animation.duration.inWholeMilliseconds.toDouble()
          easing = animation.easing.toEasingFunction()
        },
      )
    }
  }

  override suspend fun animateCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    animation: CameraAnimation,
    guard: CameraCommandGuard?,
  ) {
    awaitCameraRelease(guard = guard) { map ->
      map.cameraPositionForBounds(boundingBox, bearing, pitch, cameraPadding, fitPadding)?.let {
        map.animateTo(it.toCameraUpdate(), animation)
      }
    }
  }

  private fun MaplibreMap.animateTo(update: CameraUpdate, animation: CameraAnimation) {
    when (animation) {
      is CameraAnimation.Ease ->
        easeTo(
          unsafeJso<EaseToOptions> {
            applyUpdate(update)
            duration = animation.duration.inWholeMilliseconds.toDouble()
            easing = animation.easing.toEasingFunction()
          },
        )
      is CameraAnimation.Fly ->
        flyTo(
          unsafeJso<FlyToOptions> {
            applyUpdate(update)
            // A null property is not an absent one: GL JS reads `duration: null` as zero.
            animation.duration?.let { duration = it.inWholeMilliseconds.toDouble() }
            screenSpeed = animation.screenSpeed ?: animation.speed
            animation.minZoom?.let { minZoom = it }
            curve = animation.curve
            animation.maxDuration?.let { maxDuration = it.inWholeMilliseconds.toDouble() }
            easing = animation.easing.toEasingFunction()
          },
        )
    }
  }

  private fun CubicBezier.toEasingFunction(): (Double) -> Double {
    val curve = CubicBezierEasing(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
    return { t -> curve.transform(t.toFloat()).toDouble() }
  }

  private fun MaplibreMap.cameraPositionForBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition? {
    val current = readCameraPosition(viewportInsets)
    val destination = current.copy(padding = cameraPadding ?: current.padding)
    val persistentPadding = destination.effectivePadding()
    val combinedPadding = (persistentPadding.toCameraPadding() + fitPadding).toPaddingOptions()
    val camera =
      cameraForBounds(
        boundingBox.toLngLatBounds(),
        unsafeJso<CameraForBoundsOptions> {
          this.bearing = bearing
          this.pitch = pitch
          absolutePadding = true
          padding = combinedPadding
          maxZoom = getMaxZoom()
        },
      ) ?: return null
    // GL JS returns a camera whose persistent padding includes the space around the bounds.
    // Keep that screen placement while restoring only the destination's persistent padding.
    val fitted = _camera.transform.clone()
    fitted.setPadding(combinedPadding)
    fitted.setBearing(camera.bearing)
    fitted.setPitch(camera.pitch)
    fitted.setZoom(camera.zoom)
    fitted.setCenter(camera.center)
    val boundsCenterPoint = fitted.centerPoint
    fitted.setPadding(persistentPadding)
    fitted.setLocationAtPoint(camera.center, boundsCenterPoint)
    return destination.copy(
      center = fitted.center.toPosition(),
      zoom = camera.zoom.coerceIn(getMinZoom(), getMaxZoom()),
      bearing = camera.bearing,
      pitch = camera.pitch,
    )
  }

  private fun MaplibreMap.cameraPositionForPositions(
    positions: Sequence<Position>,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition? {
    val current = readCameraPosition(viewportInsets)
    val destination = current.copy(padding = cameraPadding ?: current.padding)
    val extent = appliedExtent
    val width = extent.width.toDouble()
    val height = extent.height.toDouble()
    val edgePadding = destination.effectivePadding()
    val fitPaddingOptions = fitPadding.toPaddingOptions()
    val minZoom = getMinZoom()
    val maxZoom = getMaxZoom()
    val flat =
      fitPositions(
        positions = positions,
        bearing = bearing,
        zoom = getZoom(),
        width = width,
        height = height,
        edgePadding = edgePadding,
        fitPadding = fitPaddingOptions,
        minZoom = minZoom,
        maxZoom = maxZoom,
      ) ?: return null
    val fit =
      refineFitForPitch(
        transform = _camera.transform,
        fit = flat,
        positions = positions,
        bearing = bearing,
        pitch = pitch,
        width = width,
        height = height,
        edgePadding = edgePadding,
        fitPadding = fitPaddingOptions,
        minZoom = minZoom,
        maxZoom = maxZoom,
      )
    return destination.copy(bearing = bearing, center = fit.target, pitch = pitch, zoom = fit.zoom)
  }

  override fun setCameraConstraints(value: CameraConstraints) {
    if (value == cameraConstraints) return
    cameraConstraints = value
    onMap { applyCameraConstraints(it, value) }
  }

  override fun getCameraConstraints(): CameraConstraints =
    cameraConstraints ?: CameraConstraints.Standard

  private fun applyCameraConstraints(map: MaplibreMap, value: CameraConstraints) {
    if (map.getMaxBounds()?.toBoundingBox() != value.boundingBox) {
      map.setMaxBounds(value.boundingBox?.toLngLatBounds())
    }

    val minZoom = map.getMinZoom()
    val maxZoom = map.getMaxZoom()
    if (value.minZoom < minZoom) map.setMinZoom(value.minZoom)
    if (value.maxZoom > maxZoom) map.setMaxZoom(value.maxZoom)
    if (value.minZoom > minZoom) map.setMinZoom(value.minZoom)
    if (value.maxZoom < maxZoom) map.setMaxZoom(value.maxZoom)

    val minPitch = map.getMinPitch()
    val maxPitch = map.getMaxPitch()
    if (value.minPitch < minPitch) map.setMinPitch(value.minPitch)
    if (value.maxPitch > maxPitch) map.setMaxPitch(value.maxPitch)
    if (value.minPitch > minPitch) map.setMinPitch(value.minPitch)
    if (value.maxPitch < maxPitch) map.setMaxPitch(value.maxPitch)
  }

  override fun getVisibleBounds(): VisibleBounds =
    withMap(VisibleBounds(Position(0.0, 0.0), Position(0.0, 0.0))) {
      it.getBounds().toVisibleBounds()
    }

  override fun getVisibleRegion(): VisibleRegion =
    withMap(
      VisibleRegion(Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0)),
    ) { map ->
      map.readVisibleRegion(appliedExtent.width.toDouble(), appliedExtent.height.toDouble())
    }

  override fun getViewport(): Viewport? =
    withMap(null as Viewport?) { map ->
      // GL JS adopts a resize synchronously in applyExtent, so the applied extent, the bounds, and
      // the transform the conversions read all describe the same viewport here.
      val extent = appliedExtent
      if (extent.isEmpty) return@withMap null
      map.readViewport(extent.width.toDouble(), extent.height.toDouble(), viewportInsets)
    }

  override fun setRenderSettings(value: RenderOptions) {
    renderSettings = value
    if (maximumFps != value.maximumFps) {
      maximumFps = value.maximumFps
      surface?.requestFrame()
    }
    onMap(::applyRenderSettings)
  }

  private fun applyRenderSettings(map: MaplibreMap) {
    map.showTileBoundaries = renderSettings.debug.tileBorders
    map.showCollisionBoxes = renderSettings.debug.collisionBoxes
    map.showPadding = renderSettings.debug.padding
    map.showOverdrawInspector = renderSettings.debug.overdrawInspector
  }

  override fun setTileLodSettings(value: TileLodOptions) {
    if (value == tileLodOptions) return
    tileLodOptions = value
    onMap(::applyTileLod)
  }

  /**
   * GL JS stores these parameters on each source. A style load or a source added later would
   * otherwise keep MapLibre's own defaults.
   */
  private fun applyTileLod(map: MaplibreMap) {
    if (!map.isStyleLoaded()) return
    map.setSourceTileLodParams(
      tileLodOptions.maxZoomLevelsOnScreen,
      tileLodOptions.tileCountMaxMinRatio,
    )
  }

  override fun positionFromScreenLocation(offset: DpOffset): Position? =
    withMap(null) { map -> map.unprojectAt(offset.x.value.toDouble(), offset.y.value.toDouble()) }

  override fun screenLocationFromPosition(position: Position): DpOffset? =
    withMap(null) { map ->
      // GL JS projects a longitude as given, while MapLibre Native wraps it onto the world copy
      // nearest the camera. Wrap here so both engines share one contract.
      val center = map.getCenter()
      val nearestCopy = with(AngleMath) { center.lng + position.longitude.diff(center.lng) }
      map.project(LngLat(lng = nearestCopy, lat = position.latitude)).toDpOffset()
    }

  override fun overlayScreenLocationFromPosition(position: Position): DpOffset? {
    val projection = renderedProjection ?: return null
    val geometry = presentedGeometry
    if (projection.target != null && geometry?.target !== projection.target) return null
    fun toScreen(point: DpOffset): DpOffset {
      if (projection.target == null || geometry == null) return point
      val source = projection.extent
      val destination = geometry.extent
      if (source.isEmpty || destination.isEmpty) return point
      return DpOffset(
        (point.x.value * source.scaleFactor * destination.physicalWidth /
            source.physicalWidth /
            destination.scaleFactor)
          .dp,
        (point.y.value * source.scaleFactor * destination.physicalHeight /
            source.physicalHeight /
            destination.scaleFactor)
          .dp,
      )
    }
    projection.locations[position]?.let {
      return toScreen(it)
    }
    if (projection.terrainChanged) {
      surface?.requestFrame()
      return null
    }
    val center = projection.transform.center
    val nearestCopy = with(AngleMath) { center.lng + position.longitude.diff(center.lng) }
    val point =
      projection.transform
        .locationToScreenPoint(
          LngLat(lng = nearestCopy, lat = position.latitude),
          projection.terrain,
        )
        .toDpOffset()
        .also { if (projection.terrain != null) projection.locations[position] = it }
    return toScreen(point)
  }

  override suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    query(DpRect(offset.x, offset.y, offset.x, offset.y), layerIds, predicate)

  override suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> = query(rect, layerIds, predicate)

  private fun query(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    withMap(emptyList()) { map ->
      // GL JS errors on a layer id its style lacks, where Native ignores it.
      val known = layerIds?.filter {
        map.getLayer(it)?.type?.let { type -> type != "custom" } == true
      }
      val options =
        unsafeJso<QueryRenderedFeaturesOptions> {
          known?.let { layers = it.toTypedArray() }
          filter = predicate?.toStyleJson()?.toJsValue<FilterSpecification>()
        }
      val geometry =
        if (rect.left == rect.right && rect.top == rect.bottom)
          queryPoint(rect.left.value.toDouble(), rect.top.value.toDouble())
        else
          queryBox(
            DpOffset(rect.left, rect.top).toPoint(),
            DpOffset(rect.right, rect.bottom).toPoint(),
          )
      val features =
        if (known != null && known.isEmpty()) mutableListOf()
        else
          map
            .queryRenderedFeatures(geometry, options)
            .map { it.layer.id to it.toGeoJsonFeature() }
            .toMutableList()
      // Native's dynamic indicator index also bypasses source-feature predicates.
      val indicators = styleBinding?.indicatorFeatures(rect, layerIds).orEmpty()
      if (indicators.isEmpty()) return@withMap features.map { it.second }
      val order = map.getLayersOrder().withIndex().associate { it.value to it.index }
      for (hit in indicators) {
        val index = features.indexOfFirst { order.getValue(it.first) < order.getValue(hit.first) }
        features.add(if (index < 0) features.size else index, hit)
      }
      features.map { it.second }
    }

  override fun metersPerDpAtLatitude(latitude: Double): Double =
    metersPerDpAtLatitude(getCameraPosition().zoom, latitude)

  // endregion

  // region camera transitions

  private suspend fun awaitCameraRelease(
    gestureToken: CameraInputToken? = null,
    guard: CameraCommandGuard? = null,
    anchored: Boolean = false,
    start: (MaplibreMap) -> Unit,
  ) = suspendCancellableCoroutine { continuation ->
    val release = { if (continuation.isActive) continuation.resume(Unit) }
    if (closing) release()
    else {
      cameraTransitions.start(
        map?.takeIf { hasLoadedInitialStyle },
        continuation,
        anchored,
      ) command@{ current ->
        runCameraCommand(
          gestureToken,
          guard,
          activate = { activateGesture(gestureToken) },
        ) {
          if (!continuation.isActive) return@command false
          engineCall { start(current) }
        }
      }
    }
  }

  override fun interruptCamera() {
    stopCameraMovement(lifecycleAuthority.gestureCamera.beginProgrammatic())
  }

  override fun stopCameraMovement(guard: CameraCommandGuard) {
    if (!guard.isValid()) return
    cameraTransitions.releasePending()
    onMap { if (guard.isValid()) it.stop() }
  }

  // endregion

  // region input, called from Compose

  private var activeGestureToken: CameraInputToken? = null

  override val isGestureReady: Boolean
    get() = canPresentFrames && hasUsableViewport && !closing && map != null

  override fun observeInput(): Long = lifecycleAuthority.gestureCamera.observeInput()

  override val inputGeneration: Long
    get() = lifecycleAuthority.gestureCamera.generation

  override fun onGestureStartedIfCurrent(generation: Long): CameraInputToken? =
    lifecycleAuthority.gestureCamera.acquireIfCurrent(this, generation)

  override fun onGestureStarted(): CameraInputToken =
    lifecycleAuthority.gestureCamera.acquire(this).also { token ->
      // Recognition takes over an existing transition even before the first movement.
      if (token.acceptsCommands) cameraTransitions.releasePending()
      onGestureMap(token) {}
    }

  override fun onGestureEnded(token: CameraInputToken) = finishGesture(token, cancelled = false)

  override fun cancelGesture(token: CameraInputToken) = finishGesture(token, cancelled = true)

  private fun finishGesture(token: CameraInputToken, cancelled: Boolean) {
    token.finish(cancelled) {
      if (activeGestureToken === token) {
        if (cancelled) onMap { it.stop() }
        if (activeGestureToken === token) {
          activeGestureToken = null
          reportGestureActive(false)
        }
      }
      token.complete()
    }
  }

  /** Reports on each command, after checking authority at execution. */
  private fun activateGesture(token: CameraInputToken?) {
    if (token == null) return
    if (activeGestureToken !== token) {
      onMap { it.stop() }
      if (!token.acceptsCommands) return
      activeGestureToken = token
    }
    reportGestureActive(true)
  }

  private fun onGestureMap(token: CameraInputToken?, action: (MaplibreMap) -> Unit) {
    if (!isGestureReady) return
    onMap { map ->
      if (isGestureReady)
        runCameraCommand(token, activate = { activateGesture(token) }) { action(map) }
    }
  }

  private fun reportGestureActive(active: Boolean) {
    if (presenting) callbacks.onGestureActive(this, active)
  }

  override fun moveBy(deltaX: Double, deltaY: Double, gestureToken: CameraInputToken?) {
    onGestureMap(gestureToken) { map ->
      map.panBy(panOffset(deltaX, deltaY), animation(Duration.ZERO))
    }
  }

  override suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) {
    awaitCameraRelease(gestureToken = gestureToken) { map ->
      map.panBy(panOffset(deltaX, deltaY), animation(duration))
    }
  }

  /** `panBy` moves the viewport by the offset, where a drag moves the content by it. */
  private fun panOffset(deltaX: Double, deltaY: Double): Point = unsafeJso {
    x = -deltaX
    y = -deltaY
  }

  override fun scaleBy(scale: Double, anchor: DpOffset?, gestureToken: CameraInputToken?) {
    onGestureMap(gestureToken) { map ->
      map.easeTo(zoomOptions(map, scale, anchor, Duration.ZERO))
    }
  }

  override suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) {
    awaitCameraRelease(gestureToken = gestureToken) { map ->
      map.easeTo(zoomOptions(map, scale, anchor, duration))
    }
  }

  override suspend fun fitBoundsAwaitingTransition(
    fit: BoxZoomFit,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) {
    awaitCameraRelease(gestureToken = gestureToken) { map ->
      map.cameraPositionForBounds(fit.bounds, fit.bearing, fit.pitch, null, DpPadding.Zero)?.let {
        map.easeTo(it.toEaseToOptions(duration))
      }
    }
  }

  private fun zoomOptions(
    map: MaplibreMap,
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
  ): EaseToOptions = unsafeJso {
    // A scale factor is a zoom delta in log space; MapLibre's zoom is already logarithmic.
    zoom = map.getZoom() + log2(scale)
    anchor?.let {
      around = map.unprojectAt(it.x.value.toDouble(), it.y.value.toDouble()).toLngLat()
    }
    this.duration = duration.inWholeMilliseconds.toDouble()
  }

  override fun rotateAndPitchBy(
    bearingDelta: Double,
    pitchDelta: Double,
    anchor: DpOffset?,
    gestureToken: CameraInputToken?,
    feedback: Boolean,
  ) {
    onGestureMap(gestureToken) { map ->
      val before = map.getBearing()
      map.easeTo(rotateOptions(map, bearingDelta, pitchDelta, anchor, Duration.ZERO))
      if (feedback && bearingDelta != 0.0) gestureToken?.reportRotation(before, map.getBearing())
    }
  }

  override suspend fun snapBearingAwaitingTransition(
    snapping: BearingSnapping,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) {
    awaitCameraRelease(gestureToken = gestureToken) { map ->
      val current = map.getBearing()
      snapping.delta(current)?.let { delta ->
        map.easeTo(
          unsafeJso<EaseToOptions> {
            bearing = current + delta
            this.duration = duration.inWholeMilliseconds.toDouble()
          },
        )
      }
    }
  }

  override suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: CameraInputToken,
    anchor: DpOffset?,
  ) {
    awaitCameraRelease(gestureToken = gestureToken) { map ->
      map.easeTo(rotateOptions(map, bearingDelta, pitchDelta, anchor, duration))
    }
  }

  /**
   * The pitch is unclamped: MapLibre holds it to the range `setMinPitch` and `setMaxPitch` gave it.
   */
  private fun rotateOptions(
    map: MaplibreMap,
    bearingDelta: Double,
    pitchDelta: Double,
    anchor: DpOffset?,
    duration: Duration,
  ): EaseToOptions = unsafeJso {
    bearing = map.getBearing() + bearingDelta
    pitch = map.getPitch() + pitchDelta
    anchor?.let {
      around = map.unprojectAt(it.x.value.toDouble(), it.y.value.toDouble()).toLngLat()
    }
    this.duration = duration.inWholeMilliseconds.toDouble()
  }

  private fun animation(duration: Duration): EaseToOptions = unsafeJso {
    this.duration = duration.inWholeMilliseconds.toDouble()
  }

  // endregion

  private fun CameraPosition.toJumpToOptions(): JumpToOptions = unsafeJso {
    applyTarget(this@toJumpToOptions)
  }

  private fun CameraPosition.toEaseToOptions(duration: Duration): EaseToOptions = unsafeJso {
    applyTarget(this@toEaseToOptions)
    this.duration = duration.inWholeMilliseconds.toDouble()
  }

  /** Sets the camera fields of an options object, with the persistent camera padding. */
  private fun PaddedCameraOptions.applyTarget(position: CameraPosition) {
    center = position.center.toLngLat()
    zoom = position.zoom
    bearing = position.bearing
    pitch = position.pitch
    padding = position.effectivePadding()
  }

  private fun PaddedCameraOptions.applyUpdate(update: CameraUpdate) {
    update.center?.let { center = it.toLngLat() }
    update.zoom?.let { zoom = it }
    update.bearing?.let { bearing = it }
    update.pitch?.let { pitch = it }
    update.padding?.let { padding = CameraPosition(padding = it).effectivePadding() }
  }

  private fun CameraPosition.effectivePadding(): PaddingOptions =
    (insets.applied + padding).toPaddingOptions()

  internal companion object {
    const val OffscreenContainerStyle =
      "all:initial;position:absolute;display:block;left:-10000px;top:0;" +
        "visibility:hidden;pointer-events:none;"

    var createdCount: Int = 0
      private set
  }
}
