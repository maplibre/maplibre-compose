package org.maplibre.compose.map

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
import kotlin.math.log2
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.gljs.CameraForBoundsOptions
import org.maplibre.compose.gljs.DEFAULT_WORKER_URL
import org.maplibre.compose.gljs.EaseToOptions
import org.maplibre.compose.gljs.FilterSpecification
import org.maplibre.compose.gljs.FlyToOptions
import org.maplibre.compose.gljs.GlJsFrameTarget
import org.maplibre.compose.gljs.GlJsMapRenderer
import org.maplibre.compose.gljs.GlJsRenderTarget
import org.maplibre.compose.gljs.GlJsRuntime
import org.maplibre.compose.gljs.GlJsSubscription
import org.maplibre.compose.gljs.GlJsSurfaceSession
import org.maplibre.compose.gljs.JumpToOptions
import org.maplibre.compose.gljs.MapOptions
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.PaddingOptions
import org.maplibre.compose.gljs.Point
import org.maplibre.compose.gljs.QueryGeometry
import org.maplibre.compose.gljs.QueryRenderedFeaturesOptions
import org.maplibre.compose.gljs.SetStyleOptions
import org.maplibre.compose.gljs.isCameraEasing
import org.maplibre.compose.gljs.isTerminalStyleLoadFailure
import org.maplibre.compose.gljs.queryBox
import org.maplibre.compose.gljs.queryPoint
import org.maplibre.compose.gljs.styleJson
import org.maplibre.compose.gljs.styleUrl
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogSource
import org.maplibre.compose.resource.GlJsRequestController
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.style.StyleLoadTracker
import org.maplibre.compose.style.StylePresentation
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.StyleRequestId
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.metersPerDpAtLatitude
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toGeoJsonFeature
import org.maplibre.compose.util.toJsValue
import org.maplibre.compose.util.toLngLat
import org.maplibre.compose.util.toLngLatBounds
import org.maplibre.compose.util.toPaddingOptions
import org.maplibre.compose.util.toPoint
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toStyleJson
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import web.dom.document
import web.gl.WebGL2RenderingContext
import web.html.HTMLCanvasElement
import web.html.HTMLElement

/** The fraction of a capped frame interval a frame may arrive early and still be drawn. */
private const val FRAME_INTERVAL_SLACK = 0.1

/**
 * The map can only be built once Compose has a WebGL context to lend it and a size to take, so
 * calls before then are queued and reads answer from what was last asked for.
 */
internal class GlJsMapSession(
  private val lifecycleAuthority: MapLifecycleAuthority,
  callbacks: MapAdapter.Callbacks,
  internal var logger: MapLog?,
  internal var layoutDirection: LayoutDirection,
  private val requests: GlJsRequestController? = null,
) : MapLifecycleSession, GlJsMapRenderer, GestureTarget {

  init {
    createdCount += 1
  }

  internal var callbacks: MapAdapter.Callbacks = callbacks
  private val lifecycle by lazy { lifecycleAuthority.bind(this) }
  private val lifecycleCallbacks by lazy { MapLifecycleCallbacks(lifecycle) { this.callbacks } }
  private var lifecycleEngineIdentity: EngineMapIdentity? = null
  private var lifecycleRenderLease: RenderLease? = null
  private var lifecycleStyleRequestIdentity: StyleRequestIdentity? = null
  private var lifecycleStyleIdentity: StyleIdentity? = null
  private var styleLoadSubscription: GlJsSubscription? = null
  private var styleErrorSubscription: GlJsSubscription? = null
  private var styleDataSubscription: GlJsSubscription? = null
  private var sourceDataSubscription: GlJsSubscription? = null

  override val engineRetention: EngineRetention = EngineRetention.DESTROY

  private var map: MaplibreMap? = null

  /** MapLibre sizes its viewport from a container even when it renders nowhere near one. */
  private var container: HTMLElement? = null

  private var surface: GlJsSurfaceSession? = null

  private class PendingMapAction(
    val run: (MaplibreMap) -> Unit,
    val abandon: () -> Unit,
  )

  /** Actions accepted before Compose supplies the context used to construct the map. */
  private val pendingMapActions = mutableListOf<PendingMapAction>()

  /** Platform-access callbacks waiting for this render lease's engine map. */
  private val pendingPlatformMapAccess = mutableListOf<PendingMapAction>()

  /** Transitions MapLibre would cancel while applying the first style's camera. */
  private val pendingInitialStyleActions = mutableListOf<PendingMapAction>()

  /** Whether the current engine map has loaded its first base style. */
  private var hasLoadedInitialStyle = false

  /** True after the current engine map has applied a non-empty presentation viewport. */
  internal var hasUsableViewport by mutableStateOf(false)
    private set

  private var hasReplayedPresentationState by mutableStateOf(false)

  /** Whether the current engine map may be copied onto the visible Compose surface. */
  internal val canPresentFrames: Boolean
    get() =
      styleLoadTracker.presentation != StylePresentation.Hidden && hasReplayedPresentationState

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

  private var maximumFps: Int? = null
  private var cameraConstraints: CameraConstraints? = null
  private var tileLodOptions: TileLodOptions = TileLodOptions.Standard
  private var lastRenderTime = TimeSource.Monotonic.markNow()
  private var hasRenderedAFrame = false

  // region surface lifecycle

  override fun onSurfaceAvailable(surface: GlJsSurfaceSession) {
    if (!lifecycle.acceptsWork) return
    this.surface = surface
    surface.requestFrame()
  }

  override fun onSurfaceLost() {
    // The map's context belongs to the surface, so it cannot outlive it.
    val engine = lifecycleEngineIdentity
    val lease = lifecycleRenderLease
    if (engine != null && lease != null) {
      // The replacement keeps the presentation, and the destroyed map emits no `moveend`. A
      // gesture that ends while the replacement is attaching cannot report, so its fact is
      // withdrawn here; a gesture that continues re-reports on its next camera command.
      activeGestureToken = null
      reportGestureActive(false)
      lifecycleAuthority.endCurrentPresentationCameraChange(this)
      surface = null
      invalidateStyleBinding()
      lifecycle.beginEngineReplacement(engine, lease)
    } else {
      surface = null
    }
  }

  override fun render(target: GlJsFrameTarget, extent: MapExtent): Boolean {
    if (!lifecycle.acceptsWork || extent.isEmpty) return false
    if (styleLoadTracker.presentation == StylePresentation.Retained) return false
    // A detached map cannot later adopt a context: everything it uploaded belongs to the one it
    // has.
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

    val now = TimeSource.Monotonic.markNow()
    if (!allowRenderNow(now)) {
      // Throttled, not dropped.
      surface?.requestFrame()
      return false
    }

    if (composited != null) {
      // Skia drives this context between MapLibre's frames, so each renderer is told the other
      // moved the state.
      val mapTarget = composited.target
      mapTarget.prepareMapRender()
      map.painter.context.setDirty()
      GlJsRuntime.withDrawingBufferSize(mapTarget.gl, mapTarget.widthPx, mapTarget.heightPx) {
        map.redraw()
      }
      mapTarget.resetSkiaState()
    } else {
      // GL JS runs style updates, tile loading and every camera ease from inside its own render, so
      // even a map nothing samples has to be asked to draw.
      map.redraw()
    }

    if (!hasRenderedAFrame) {
      hasRenderedAFrame = true
      logger?.i {
        "Rendered the first map frame at ${extent.physicalWidth}x${extent.physicalHeight}"
      }
    }
    lastRenderTime = now
    return true
  }

  internal fun detachedCanvas(): HTMLCanvasElement? =
    if (lentContext != null) null else map?.getCanvas()

  override fun close() {
    lifecycle.close()
  }

  override suspend fun awaitClosed() {
    lifecycle.awaitClosed()
  }

  fun start() {
    lifecycle.beginAttachIfOpen()
  }

  override suspend fun createEngine(identity: EngineMapIdentity) {
    lifecycleEngineIdentity = identity
    lifecycleStyleRequestIdentity = lifecycle.claimStyleRequestIdentity(identity)
  }

  override suspend fun attach(identity: EngineMapIdentity, lease: RenderLease) {
    lifecycleRenderLease = lease
  }

  override suspend fun detach(identity: EngineMapIdentity, lease: RenderLease) {
    if (lifecycleRenderLease == lease) lifecycleRenderLease = null
  }

  override suspend fun destroyEngine(identity: EngineMapIdentity) {
    if (lifecycleEngineIdentity == identity) {
      lifecycleEngineIdentity = null
      lifecycleStyleRequestIdentity = null
      lifecycleStyleIdentity = null
    }
    abandonPending(pendingPlatformMapAccess)
    destroyMap()
  }

  override suspend fun closeResources() {
    activeGestureToken = null
    abandonPending(pendingMapActions)
    abandonPending(pendingInitialStyleActions)
    abandonPending(pendingPlatformMapAccess)
    surface = null
  }

  /**
   * MapLibre takes its WebGL context and its size at construction, so the map cannot exist before
   * the first frame that has somewhere to draw. A null [target] builds a detached map, which takes
   * a context from its own canvas and is never drawn.
   */
  private fun ensureMap(target: GlJsRenderTarget?, extent: MapExtent): MaplibreMap? {
    map?.let {
      return it
    }
    if (!lifecycle.acceptsWork) return null
    if (!lifecycleAuthority.selectAdapterForPresentation(this)) return null

    val host = document.createElement("div").unsafeCast<HTMLElement>()
    host.style.cssText = OFFSCREEN_CONTAINER_STYLE
    host.style.width = "${extent.width}px"
    host.style.height = "${extent.height}px"
    document.body.appendChild(host)
    container = host

    val options =
      unsafeJso<MapOptions> {
        this.container = host
        // Gestures arrive through GestureTarget below.
        interactive = false
        attributionControl = false
        maplibreLogo = false
        pixelRatio = extent.scaleFactor
        target?.let {
          // MapLibre otherwise clamps its pixel ratio to the drawing buffer of the canvas it
          // shares, which is Compose's whole viewport at the moment the map was built.
          maxCanvasSize = maxTextureSize(it.gl)
        }
        requests?.let { controller ->
          transformRequest = { url, resourceType -> controller.transformRequest(url, resourceType) }
        }
      }
    GlJsRuntime.pointAtWorker(DEFAULT_WORKER_URL)
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
    val engine = lifecycleEngineIdentity ?: return null
    val lease = lifecycleRenderLease ?: return null
    wireEvents(created, engine, lease)

    map = created
    hasLoadedInitialStyle = false
    appliedExtent = MapExtent.Empty
    cameraConstraints?.let { applyCameraConstraints(created, it) }
    runPending(pendingMapActions, created)
    runPending(pendingPlatformMapAccess, created)
    return created.takeIf { lifecycle.acceptsWork && map === created }
  }

  private fun destroyMap() {
    hasLoadedInitialStyle = false
    val current = map ?: return
    map = null
    hasUsableViewport = false
    hasReplayedPresentationState = false
    invalidateStyleBinding()
    current.setMissingStyleImageResolver(null)
    styleLoadSubscription?.cancel()
    styleLoadSubscription = null
    styleErrorSubscription?.cancel()
    styleErrorSubscription = null
    styleDataSubscription?.cancel()
    styleDataSubscription = null
    sourceDataSubscription?.cancel()
    sourceDataSubscription = null
    appliedStyleRequest = null
    styleLoadPending = false
    styleLoadTracker.engineBecameUnavailable()
    hasRenderedAFrame = false
    val borrowed = lentContext
    lentContext = null
    runCatching {
      if (borrowed == null) current.remove()
      else GlJsRuntime.removingWithoutLosingContext(borrowed) { current.remove() }
    }
      .onFailure { logger?.e(it) { "MapLibre failed to close" } }
    container?.let { runCatching { it.remove() } }
    container = null
    resumeStrandedTransitions()
  }

  private fun invalidateStyleBinding() {
    callbacks.onStyleChanged(this, null)
    styleBinding?.invalidate()
    styleBinding = null
  }

  private fun applyExtent(map: MaplibreMap, extent: MapExtent) {
    if (extent == appliedExtent) return
    appliedExtent = extent
    container?.let { host ->
      host.style.width = "${extent.width}px"
      host.style.height = "${extent.height}px"
    }
    map.setPixelRatio(extent.scaleFactor)
    map.resize()
    hasUsableViewport = true
    // resize() may also fire `move`; this report is how overlays learn the viewport changed when
    // the camera position did not. Seed here too: the first resize can land before the lease is
    // Attached, and acceptPresentationEvent then drops that callback.
    lifecycleAuthority.seedCurrentPresentationViewport(this)
    withLifecyclePresentation { engine, lease ->
      lifecycleCallbacks.onViewportChanged(engine, lease, this)
    }
  }

  /** Records that the current engine map has applied the logical map's desired state. */
  internal fun markPresentationStateReplayed() {
    if (hasReplayedPresentationState) return
    hasReplayedPresentationState = true
    map?.let(::applyRequestedStyle)
    surface?.requestFrame()
  }

  /** The current GL JS engine-map instance, exposed only to browser boundary tests. */
  internal fun engineMapForTest(): MaplibreMap? = map

  internal suspend fun <T> withPlatformMap(block: PlatformMapScope.() -> T): T {
    val engine =
      lifecycle.engineIdentity
        ?: throw CancellationException("The Web platform map changed before access could begin")
    val lease =
      lifecycle.renderLease
        ?: throw CancellationException("The Web platform map changed before access could begin")
    return suspendCancellableCoroutine { continuation ->
      val invocation = PlatformMapInvocation(continuation)
      lateinit var action: PendingMapAction
      action =
        PendingMapAction(
          run = { map ->
            invocation.execute {
              var result: Result<T>? = null
              val presentationAccepted =
                lifecycle.acceptPresentationEvent(engine, lease) {
                  val authorityAccepted =
                    lifecycleAuthority.acceptPresentationPlatformAccess(this) {
                      result = runCatching { PlatformMapScope(map).block() }
                    }
                  if (!authorityAccepted) {
                    throw CancellationException(
                      "The Web platform map changed before access could begin"
                    )
                  }
                }
              if (!presentationAccepted) {
                throw CancellationException(
                  "The Web platform map changed before access could begin"
                )
              }
              checkNotNull(result).getOrThrow()
            }
          },
          abandon = {
            invocation.fail(
              CancellationException("The Web platform map changed before access could begin")
            )
          },
        )
      continuation.invokeOnCancellation {
        invocation.cancel()
        pendingPlatformMapAccess.remove(action)
      }
      val current = map
      if (current != null) action.run(current)
      else if (invocation.isQueued) pendingPlatformMapAccess += action
    }
  }

  private fun maxTextureSize(gl: dynamic): Array<Double> {
    val size = (gl.getParameter(gl.MAX_TEXTURE_SIZE) as? Int)?.toDouble() ?: 4096.0
    return arrayOf(size, size)
  }

  /**
   * A cap at the display's own rate would reject any interval measured a microsecond short, halving
   * the frame rate; hence [FRAME_INTERVAL_SLACK].
   */
  private fun allowRenderNow(now: TimeSource.Monotonic.ValueTimeMark): Boolean {
    val fps = maximumFps ?: return true
    if (fps <= 0) return true
    val elapsed = (now - lastRenderTime).toDouble(DurationUnit.SECONDS)
    return elapsed >= (1.0 / fps) * (1.0 - FRAME_INTERVAL_SLACK)
  }

  // endregion

  // region events

  private fun wireEvents(map: MaplibreMap, engine: EngineMapIdentity, lease: RenderLease) {
    map.subscribe("error") { event ->
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
      // The style is whichever one is loaded when MapLibre asks, so the identity is read here
      // rather than captured with the resolver.
      val style = lifecycleStyleIdentity
      val resolution =
        if (style == null) null
        else lifecycleCallbacks.resolveMissingImage(engine, style, this, imageId)
      resolution?.asPromise()
    }

    subscribeTranslated(map, ENGINE_GL_JS_EVENTS) { lifecycleCallbacks.onEvent(engine, this, it) }
    subscribeTranslated(map, PRESENTATION_GL_JS_EVENTS) { event ->
      val accepted = lifecycleCallbacks.onEvent(engine, lease, this, event)
      // A `moveend` is how GL JS reports that an eased transition finished.
      if (accepted && event is MapEvent.CameraMoveEnded) resumeTransitions()
    }
  }

  private fun subscribeTranslated(
    map: MaplibreMap,
    translations: Map<String, GlJsEventTranslation>,
    deliver: (MapEvent) -> Unit,
  ) {
    for ((type, translate) in translations) {
      map.subscribe(type) { event -> deliver(translate(event)) }
    }
  }

  private fun reportBaseStyleReady(
    engine: EngineMapIdentity,
    style: StyleIdentity,
    binding: GlJsStyleBinding,
  ) {
    if (map?.isStyleLoaded() == true && styleLoadTracker.baseStyleReady(binding.identity)) {
      try {
        lifecycleCallbacks.onStyleReady(engine, style, this)
      } catch (error: Throwable) {
        styleLoadTracker.failed(binding.identity)
        throw error
      }
    }
  }

  // endregion

  // region dispatch

  private fun onMap(action: (MaplibreMap) -> Unit) {
    postWhenMapExists(PendingMapAction(action, abandon = {}))
  }

  private fun postWhenMapExists(action: PendingMapAction) {
    if (!lifecycle.acceptsWork) {
      action.abandon()
      return
    }
    val current = map
    if (current == null) pendingMapActions += action else action.run(current)
  }

  private fun postWhenInitialStyleLoaded(action: PendingMapAction) {
    if (!lifecycle.acceptsWork) {
      action.abandon()
      return
    }
    val current = map
    if (current == null || !hasLoadedInitialStyle) pendingInitialStyleActions += action
    else action.run(current)
  }

  private fun runPending(actions: MutableList<PendingMapAction>, map: MaplibreMap) {
    val running = actions.toList()
    actions.clear()
    running.forEach { it.run(map) }
  }

  private fun abandonPending(actions: MutableList<PendingMapAction>) {
    val abandoned = actions.toList()
    actions.clear()
    abandoned.forEach { it.abandon() }
  }

  private fun <T> withMap(fallback: T, action: (MaplibreMap) -> T): T = map?.let(action) ?: fallback

  // endregion

  // region MapAdapter

  override fun setBaseStyle(style: BaseStyle) {
    if (style == requestedStyle) return
    // Must precede the new style: the old style's sources and layers would otherwise recompose
    // against base layers being replaced.
    styleBinding?.invalidate()
    requestedStyle = style
    styleLoadTracker.request()
    lifecycleEngineIdentity?.let {
      lifecycleStyleRequestIdentity = lifecycleCallbacks.beginStyleRequest(it, this)
    }
    lifecycleStyleIdentity = null
    if (hasReplayedPresentationState) onMap(::applyRequestedStyle)
  }

  override suspend fun reconcileStyleRevision(revision: DesiredStyleRevision) {
    val binding = styleBinding ?: return
    val engine = lifecycleEngineIdentity ?: return
    val style = lifecycleStyleIdentity ?: return
    if (!styleLoadTracker.beginReconciliation(binding.identity)) return
    try {
      styleReconciler.apply(binding, revision)
      if (styleLoadTracker.reconciled(binding.identity)) {
        lifecycleCallbacks.onStyleReady(engine, style, this)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      styleLoadTracker.failed(binding.identity)
      throw error
    }
    surface?.requestFrame()
  }

  override suspend fun replayStyleRevision(revision: DesiredStyleRevision) {
    val binding = styleBinding ?: return
    if (!styleLoadTracker.beginReconciliation(binding.identity)) return
    try {
      styleReconciler.apply(binding, revision)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      styleLoadTracker.failed(binding.identity)
      throw error
    }
    surface?.requestFrame()
  }

  private fun applyRequestedStyle(map: MaplibreMap) {
    val style = requestedStyle ?: return
    if (styleLoadPending) return
    val trackerRequest = styleLoadTracker.requestId
    if (appliedStyleRequest == trackerRequest) return
    appliedStyleRequest = trackerRequest
    styleLoadPending = true
    val engine = lifecycleEngineIdentity ?: return
    val lifecycleRequest = lifecycleStyleRequestIdentity ?: return
    styleLoadSubscription?.cancel()
    styleErrorSubscription?.cancel()
    styleDataSubscription?.cancel()
    sourceDataSubscription?.cancel()
    lateinit var loadSubscription: GlJsSubscription
    lateinit var errorSubscription: GlJsSubscription
    loadSubscription =
      map.subscribe("style.load") {
        loadSubscription.cancel()
        errorSubscription.cancel()
        if (styleLoadSubscription === loadSubscription) styleLoadSubscription = null
        if (styleErrorSubscription === errorSubscription) styleErrorSubscription = null
        styleLoadPending = false
        val binding = GlJsStyleBinding(map, logger) { appliedExtent.scaleFactor.toFloat() }
        if (!styleLoadTracker.loaded(trackerRequest, binding.identity, map.isStyleLoaded())) {
          binding.invalidate()
          applyRequestedStyle(map)
          return@subscribe
        }
        val acceptedStyle =
          lifecycleCallbacks.onStyleChanged(engine, lifecycleRequest, this, binding)
        if (acceptedStyle != null) {
          styleBinding?.invalidate()
          styleBinding = binding
          lifecycleStyleIdentity = acceptedStyle
          lifecycleCallbacks.onEvent(engine, acceptedStyle, this, MapEvent.StyleLoaded)
          styleDataSubscription =
            map.subscribe("styledata") {
              reportBaseStyleReady(engine, acceptedStyle, binding)
            }
          sourceDataSubscription =
            map.subscribe("sourcedata") { event ->
              reportBaseStyleReady(engine, acceptedStyle, binding)
              if (event.sourceDataType == "metadata") {
                applyTileLod(map)
                event.sourceId?.let {
                  lifecycleCallbacks.onStyleSourcesChanged(engine, acceptedStyle, this, it)
                }
              }
            }
          applyTileLod(map)
          if (!hasLoadedInitialStyle) {
            hasLoadedInitialStyle = true
            runPending(pendingInitialStyleActions, map)
          }
        } else {
          binding.invalidate()
        }
      }
    errorSubscription =
      map.subscribe("error") { event ->
        if (!event.isTerminalStyleLoadFailure()) return@subscribe
        loadSubscription.cancel()
        errorSubscription.cancel()
        if (styleLoadSubscription === loadSubscription) styleLoadSubscription = null
        if (styleErrorSubscription === errorSubscription) styleErrorSubscription = null
        val reason = event.error?.message ?: "MapLibre failed to load the map"
        styleLoadPending = false
        val accepted = styleLoadTracker.failed(trackerRequest)
        if (accepted) {
          if (lifecycleCallbacks.onStyleFailed(engine, lifecycleRequest, this, reason)) {
            logger?.e { "Map loading failed: $reason" }
            if (!hasLoadedInitialStyle) abandonPending(pendingInitialStyleActions)
            lifecycleCallbacks.onEvent(
              engine,
              lifecycleRequest,
              this,
              MapEvent.StyleLoadFailed(reason),
            )
          }
        } else {
          applyRequestedStyle(map)
        }
      }
    styleLoadSubscription = loadSubscription
    styleErrorSubscription = errorSubscription
    // MapLibre diffs by default, keeping the same Style object, so no `style.load` would fire.
    val options = unsafeJso<SetStyleOptions> { diff = false }
    try {
      when (style) {
        is BaseStyle.Uri -> map.setStyle(styleUrl(style.uri), options)
        is BaseStyle.Json -> map.setStyle(styleJson(style.json), options)
      }
    } catch (error: Throwable) {
      // An inline style is parsed here rather than fetched, so a malformed one throws where every
      // other load failure arrives as an `error` event.
      styleLoadSubscription?.cancel()
      styleLoadSubscription = null
      styleErrorSubscription?.cancel()
      styleErrorSubscription = null
      styleLoadPending = false
      val reason = error.message ?: "MapLibre failed to load the map"
      logger?.e(error) { "Map loading failed: $reason" }
      if (styleLoadTracker.failed(trackerRequest)) {
        lifecycleCallbacks.onStyleFailed(engine, lifecycleRequest, this, reason)
        lifecycleCallbacks.onEvent(
          engine,
          lifecycleRequest,
          this,
          MapEvent.StyleLoadFailed(reason),
        )
      }
      if (!hasLoadedInitialStyle) abandonPending(pendingInitialStyleActions)
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
  private var cameraPadding: PaddingOptions = PaddingValues(0.dp).toPaddingOptions(layoutDirection)

  override fun getCameraPosition(): CameraPosition =
    withMap(requestedCamera ?: CameraPosition()) { map -> map.cameraPosition() }

  private fun MaplibreMap.cameraPosition(): CameraPosition =
    CameraPosition(
      bearing = getBearing(),
      target = getCenter().toPosition(),
      tilt = getPitch(),
      zoom = getZoom(),
    )

  override fun setCameraPosition(cameraPosition: CameraPosition, guard: CameraCommandGuard?) {
    if (guard?.isValid() == false) return
    requestedCamera = cameraPosition
    onMap { map -> if (guard?.isValid() != false) map.jumpTo(cameraPosition.toJumpToOptions()) }
  }

  override fun setCameraPadding(padding: PaddingValues) {
    val resolved = padding.toPaddingOptions(layoutDirection)
    if (cameraPadding.sameAs(resolved)) return
    cameraPadding = resolved
    onMap { map -> map.jumpTo(unsafeJso<JumpToOptions> { this.padding = resolved }) }
  }

  override fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    guard: CameraCommandGuard?,
  ) {
    if (guard?.isValid() == false) return
    onMap { map ->
      if (guard?.isValid() == false) return@onMap
      map.cameraPositionForBounds(boundingBox, bearing, tilt, padding)?.let {
        map.jumpTo(it.toJumpToOptions())
      }
    }
  }

  override suspend fun animateCameraPosition(
    finalPosition: CameraPosition,
    duration: Duration,
    guard: CameraCommandGuard?,
  ) {
    awaitCameraRelease(guard = guard) { map ->
      map.flyTo(
        unsafeJso<FlyToOptions> {
          center = finalPosition.target.toLngLat()
          zoom = finalPosition.zoom
          bearing = finalPosition.bearing
          pitch = finalPosition.tilt
          padding = cameraPadding
          this.duration = duration.inWholeMilliseconds.toDouble()
        }
      )
    }
  }

  override suspend fun animateCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
    guard: CameraCommandGuard?,
  ) {
    awaitCameraRelease(guard = guard) { map ->
      map.cameraPositionForBounds(boundingBox, bearing, tilt, padding)?.let {
        map.easeTo(it.toEaseToOptions(duration))
      }
    }
  }

  private fun MaplibreMap.cameraPositionForBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ): CameraPosition? {
    val previous = cameraPosition()
    val result =
      cameraForBounds(boundingBox.toLngLatBounds(), cameraForBoundsOptions(bearing, padding))
        ?: return null
    return CameraPosition(
      bearing = result.bearing ?: bearing,
      target = result.center?.toPosition() ?: previous.target,
      tilt = tilt,
      zoom = result.zoom ?: previous.zoom,
    )
  }

  private fun cameraForBoundsOptions(
    bearing: Double,
    padding: PaddingValues,
  ): CameraForBoundsOptions = unsafeJso {
    this.bearing = bearing
    this.padding = padding.toPaddingOptions(layoutDirection)
  }

  override fun setCameraConstraints(value: CameraConstraints) {
    if (value == cameraConstraints) return
    cameraConstraints = value
    map?.let { applyCameraConstraints(it, value) }
  }

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

  override fun getVisibleBoundingBox(): BoundingBox =
    withMap(BoundingBox(Position(0.0, 0.0), Position(0.0, 0.0))) { it.getBounds().toBoundingBox() }

  override fun getVisibleRegion(): VisibleRegion =
    withMap(
      VisibleRegion(Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0))
    ) { map ->
      map.readVisibleRegion(appliedExtent.width.toDouble(), appliedExtent.height.toDouble())
    }

  override fun getViewport(): Viewport? =
    withMap(null as Viewport?) { map ->
      // GL JS adopts a resize synchronously in applyExtent, so the applied extent, the bounds, and
      // the transform the conversions read all describe the same viewport here.
      val extent = appliedExtent
      if (extent.isEmpty) return@withMap null
      map.readViewport(extent.width.toDouble(), extent.height.toDouble())
    }

  override fun setRenderSettings(value: RenderOptions) {
    maximumFps = value.maximumFps
    onMap { map ->
      map.showTileBoundaries = value.isTileBordersEnabled
      map.showCollisionBoxes = value.isCollisionBoxesEnabled
      map.showPadding = value.isPaddingEnabled
      map.showOverdrawInspector = value.isOverdrawInspectorEnabled
    }
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
    withMap(null) { map -> map.project(position.toLngLat()).toDpOffset() }

  override suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    query(queryPoint(offset.x.value.toDouble(), offset.y.value.toDouble()), layerIds, predicate)

  override suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    query(
      queryBox(
        DpOffset(rect.left, rect.top).toPoint(),
        DpOffset(rect.right, rect.bottom).toPoint(),
      ),
      layerIds,
      predicate,
    )

  private fun query(
    geometry: QueryGeometry,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    withMap(emptyList()) { map ->
      // GL JS errors on a layer id its style lacks, where Native ignores it.
      val known = layerIds?.filter { map.getLayer(it) != null }
      if (known != null && known.isEmpty()) return@withMap emptyList()
      val options =
        unsafeJso<QueryRenderedFeaturesOptions> {
          known?.let { layers = it.toTypedArray() }
          filter = predicate?.toStyleJson()?.toJsValue<FilterSpecification>()
        }
      map.queryRenderedFeatures(geometry, options).map { it.toGeoJsonFeature() }
    }

  override fun metersPerDpAtLatitude(latitude: Double): Double =
    metersPerDpAtLatitude(getCameraPosition().zoom, latitude)

  // endregion

  // region camera transitions

  private val transitionWaiters = mutableListOf<CancellableContinuation<Unit>>()

  /**
   * Resumes normally however the transition ended: a `moveend` does not say whether this transition
   * finished it or a later command took it over.
   */
  private suspend fun awaitCameraRelease(
    gestureToken: GestureToken? = null,
    guard: CameraCommandGuard? = null,
    start: (MaplibreMap) -> Unit,
  ) = suspendCancellableCoroutine { continuation ->
    val pending =
      PendingMapAction(
        run = { current ->
          if (
            gestureToken?.canExecute != false && guard?.isValid() != false && continuation.isActive
          ) {
            activateGesture(gestureToken)
            if (gestureToken?.canExecute != false && guard?.isValid() != false)
              startTransitionOnMap(current, start, continuation)
            else if (continuation.isActive) continuation.resume(Unit)
          } else if (continuation.isActive) continuation.resume(Unit)
        },
        abandon = { if (continuation.isActive) continuation.resume(Unit) },
      )
    continuation.invokeOnCancellation {
      if (pendingInitialStyleActions.remove(pending)) return@invokeOnCancellation
      if (transitionWaiters.remove(continuation)) map?.stop()
    }
    if (
      guard?.isValid() == false ||
        gestureToken != null && !gestureToken.enqueue { postWhenInitialStyleLoaded(pending) }
    ) {
      if (continuation.isActive) continuation.resume(Unit)
    } else if (gestureToken == null) postWhenInitialStyleLoaded(pending)
  }

  private fun startTransitionOnMap(
    map: MaplibreMap,
    start: (MaplibreMap) -> Unit,
    continuation: CancellableContinuation<Unit>,
  ) {
    // Cancellation while this waits for the first style must not start a transition later.
    if (!continuation.isActive) return
    try {
      start(map)
    } catch (error: Throwable) {
      if (continuation.isActive) continuation.resumeWith(Result.failure(error))
      return
    }
    // Registered after the call rather than before it: replacing a transition ends the old one
    // from inside this call, and that `moveend` belongs to the transition being replaced. One
    // that finished inside the call leaves the map at rest instead.
    if (map.isCameraEasing()) transitionWaiters += continuation
    else if (continuation.isActive) continuation.resume(Unit)
  }

  private fun resumeTransitions() {
    if (transitionWaiters.isEmpty()) return
    val resuming = transitionWaiters.toList()
    transitionWaiters.clear()
    resuming.forEach { waiter -> if (waiter.isActive) runCatching { waiter.resume(Unit) } }
  }

  /** No `moveend` follows a map that is going away. */
  private fun resumeStrandedTransitions() {
    resumeTransitions()
  }

  override fun cancelTransitions() {
    abandonPending(pendingInitialStyleActions)
    onMap { it.stop() }
  }

  // endregion

  // region input, called from Compose

  private var activeGestureToken: GestureToken? = null

  override val isGestureReady: Boolean
    get() = canPresentFrames && hasUsableViewport && lifecycle.acceptsWork && map != null

  override fun observeInput(): Long = lifecycleAuthority.gestureCamera.observeInput()

  override val inputGeneration: Long
    get() = lifecycleAuthority.gestureCamera.generation

  override fun onGestureStartedIfCurrent(generation: Long): GestureToken? =
    lifecycleAuthority.gestureCamera.acquireIfCurrent(this, generation)

  override fun onGestureStarted(): GestureToken = lifecycleAuthority.gestureCamera.acquire(this)

  override fun onGestureEnded(token: GestureToken) = finishGesture(token, cancelled = false)

  override fun cancelGesture(token: GestureToken) = finishGesture(token, cancelled = true)

  override suspend fun awaitGestureEnded(token: GestureToken) {
    token.completion.await()
  }

  private fun finishGesture(token: GestureToken, cancelled: Boolean) {
    token.finish(cancelled) {
      if (activeGestureToken === token) {
        if (token.isCancelled) map?.stop()
        if (activeGestureToken === token) {
          activeGestureToken = null
          reportGestureActive(false)
        }
      }
      token.complete()
    }
  }

  /** Reports on each command, after checking authority at execution. */
  private fun activateGesture(token: GestureToken?) {
    if (token == null || !token.canExecute) return
    if (activeGestureToken !== token) {
      map?.stop()
      if (!token.canExecute) return
      activeGestureToken = token
    }
    reportGestureActive(true)
  }

  private fun onGestureMap(token: GestureToken?, action: (MaplibreMap) -> Unit) {
    if (!isGestureReady) return
    val enqueue = {
      onMap { map ->
        if (isGestureReady && token?.canExecute != false) {
          activateGesture(token)
          if (token?.canExecute != false) action(map)
        }
      }
    }
    if (token == null) enqueue() else token.enqueue(enqueue)
  }

  private fun reportGestureActive(active: Boolean) {
    withLifecyclePresentation { engine, lease ->
      lifecycleCallbacks.onGestureActive(engine, lease, this, active)
    }
  }

  override fun moveBy(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken?,
  ) {
    onGestureMap(gestureToken) { map -> map.panBy(panOffset(deltaX, deltaY), animation(duration)) }
  }

  override suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken,
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

  override fun scaleBy(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken?,
  ) {
    onGestureMap(gestureToken) { map -> map.easeTo(zoomOptions(map, scale, anchor, duration)) }
  }

  override suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    awaitCameraRelease(gestureToken = gestureToken) { map ->
      map.easeTo(zoomOptions(map, scale, anchor, duration))
    }
  }

  override suspend fun fitBoundsAwaitingTransition(
    fit: BoxZoomFit,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    awaitCameraRelease(gestureToken = gestureToken) { map ->
      map.cameraPositionForBounds(fit.bounds, fit.bearing, fit.tilt, PaddingValues())?.let {
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
    duration: Duration,
    anchor: DpOffset?,
    gestureToken: GestureToken?,
  ) {
    onGestureMap(gestureToken) { map ->
      map.easeTo(rotateOptions(map, bearingDelta, pitchDelta, anchor, duration))
    }
  }

  override suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: GestureToken,
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

  private inline fun withLifecyclePresentation(action: (EngineMapIdentity, RenderLease) -> Unit) {
    val engine = lifecycleEngineIdentity ?: return
    val lease = lifecycleRenderLease ?: return
    action(engine, lease)
  }

  // endregion

  private fun CameraPosition.toJumpToOptions(): JumpToOptions = unsafeJso {
    center = target.toLngLat()
    zoom = this@toJumpToOptions.zoom
    bearing = this@toJumpToOptions.bearing
    pitch = tilt
    padding = cameraPadding
  }

  private fun CameraPosition.toEaseToOptions(duration: Duration): EaseToOptions = unsafeJso {
    center = target.toLngLat()
    zoom = this@toEaseToOptions.zoom
    bearing = this@toEaseToOptions.bearing
    pitch = tilt
    padding = cameraPadding
    this.duration = duration.inWholeMilliseconds.toDouble()
  }

  private fun PaddingOptions.sameAs(other: PaddingOptions): Boolean =
    top == other.top && left == other.left && bottom == other.bottom && right == other.right

  internal companion object {
    const val OFFSCREEN_CONTAINER_STYLE =
      "all:initial;position:absolute;display:block;left:-10000px;top:0;" +
        "visibility:hidden;pointer-events:none;"

    var createdCount: Int = 0
      private set
  }
}
