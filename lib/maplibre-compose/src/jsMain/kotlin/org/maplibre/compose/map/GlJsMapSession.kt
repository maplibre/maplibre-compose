package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import js.objects.unsafeJso
import kotlin.coroutines.resume
import kotlin.math.log2
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.gljs.DEFAULT_WORKER_URL
import org.maplibre.compose.gljs.EaseToOptions
import org.maplibre.compose.gljs.FilterSpecification
import org.maplibre.compose.gljs.FitBoundsOptions
import org.maplibre.compose.gljs.FlyToOptions
import org.maplibre.compose.gljs.GlJsFrameTarget
import org.maplibre.compose.gljs.GlJsMapRenderer
import org.maplibre.compose.gljs.GlJsRenderTarget
import org.maplibre.compose.gljs.GlJsRuntime
import org.maplibre.compose.gljs.GlJsSurfaceSession
import org.maplibre.compose.gljs.JumpToOptions
import org.maplibre.compose.gljs.MapOptions
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.Point
import org.maplibre.compose.gljs.QueryGeometry
import org.maplibre.compose.gljs.QueryRenderedFeaturesOptions
import org.maplibre.compose.gljs.SetStyleOptions
import org.maplibre.compose.gljs.SkikoGpuBridge
import org.maplibre.compose.gljs.isCameraEasing
import org.maplibre.compose.gljs.queryBox
import org.maplibre.compose.gljs.styleJson
import org.maplibre.compose.gljs.styleUrl
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GlJsStyle
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.metersPerDpAtLatitude
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toGeoJsonFeature
import org.maplibre.compose.util.toJsValue
import org.maplibre.compose.util.toLngLat
import org.maplibre.compose.util.toLngLatBounds
import org.maplibre.compose.util.toPaddingOptions
import org.maplibre.compose.util.toPaddingValues
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
  internal var callbacks: MapAdapter.Callbacks,
  internal var logger: Logger?,
  internal var layoutDirection: LayoutDirection,
) : MapAdapter, GlJsMapRenderer, GestureTarget {

  private var map: MaplibreMap? = null

  /** MapLibre sizes its viewport from a container even when it renders nowhere near one. */
  private var container: HTMLElement? = null

  private var surface: GlJsSurfaceSession? = null
  private var closed = false

  private class PendingMapAction(
    val run: (MaplibreMap) -> Unit,
    val abandon: () -> Unit,
  )

  /** Actions accepted before Compose supplies the context used to construct the map. */
  private val pendingMapActions = mutableListOf<PendingMapAction>()

  /** Transitions MapLibre would cancel while applying the first style's camera. */
  private val pendingInitialStyleActions = mutableListOf<PendingMapAction>()

  /** Readiness belongs to a map instance, not to its current, replaceable style. */
  private var hasLoadedInitialStyle = false

  private var requestedStyle: BaseStyle? = null
  private var appliedStyle: BaseStyle? = null

  /** Set while a style load is outstanding, so an `error` can be told from a tile failure. */
  private var styleLoadPending = false

  private var styleBinding: GlJsStyleBinding? = null

  private var reportStyleLoaded = false

  private var appliedExtent: MapExtent = MapExtent.Empty

  private var framebuffer: Any? = null

  private var lentContext: WebGL2RenderingContext? = null

  private var maximumFps: Int? = null
  private var tileLodOptions: TileLodOptions = TileLodOptions.Standard
  private var lastRenderTime = TimeSource.Monotonic.markNow()
  private var lastFrameTime = TimeSource.Monotonic.markNow()
  private var hasRenderedAFrame = false

  // region surface lifecycle

  override fun onSurfaceAvailable(surface: GlJsSurfaceSession) {
    if (closed) return
    this.surface = surface
    surface.requestFrame()
  }

  override fun onSurfaceLost() {
    // The map's context belongs to the surface, so it cannot outlive it.
    destroyMap()
    surface = null
  }

  override fun render(target: GlJsFrameTarget, extent: MapExtent): Boolean {
    if (closed || extent.isEmpty) return false
    // A detached map cannot later adopt a context: everything it uploaded belongs to the one it
    // has.
    val composited = target as? GlJsFrameTarget.Composited
    if (target is GlJsFrameTarget.NotReady && map == null) return false
    framebuffer = composited?.target?.framebuffer
    val map = ensureMap(composited?.target, extent) ?: return false
    applyExtent(map, extent)
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
      map.painter.context.setDirty()
      map.redraw()
      SkikoGpuBridge.resetGlState()
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
    reportFrameRate()
    return true
  }

  internal fun detachedCanvas(): HTMLCanvasElement? =
    if (lentContext != null) null else map?.getCanvas()

  override fun close() {
    if (closed) return
    closed = true
    isGestureInProgress = false
    endCameraMove()
    abandonPending(pendingMapActions)
    abandonPending(pendingInitialStyleActions)
    destroyMap()
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
    if (closed) return null

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
      }
    GlJsRuntime.pointAtWorker(DEFAULT_WORKER_URL)
    val created =
      if (target == null) MaplibreMap(options)
      else {
        val context = target.gl.unsafeCast<WebGL2RenderingContext>()
        lentContext = context
        GlJsRuntime.lendingContext(context) { MaplibreMap(options) }
      }

    // Before the style resolves: any render before the redirect lands on Compose's canvas.
    if (target != null) {
      GlJsRuntime.redirectDefaultFramebuffer(created.painter.context) { framebuffer }
    }
    GlJsRuntime.interceptRepaintRequests(created) { surface?.requestFrame() }
    wireEvents(created)

    map = created
    hasLoadedInitialStyle = false
    appliedExtent = MapExtent.Empty
    applyRequestedStyle(created)
    runPending(pendingMapActions, created)
    return created
  }

  private fun destroyMap() {
    hasLoadedInitialStyle = false
    val current = map ?: return
    map = null
    styleBinding?.unload()
    styleBinding = null
    appliedStyle = null
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

  private fun applyExtent(map: MaplibreMap, extent: MapExtent) {
    if (extent == appliedExtent) return
    appliedExtent = extent
    container?.let { host ->
      host.style.width = "${extent.width}px"
      host.style.height = "${extent.height}px"
    }
    map.setPixelRatio(extent.scaleFactor)
    map.resize()
    // resize() may also fire `move`; a second onCameraMoved is how overlays learn the viewport
    // changed when the camera position did not.
    callbacks.onCameraMoved(this)
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

  private fun reportFrameRate() {
    val now = TimeSource.Monotonic.markNow()
    val elapsed = (now - lastFrameTime).toDouble(DurationUnit.SECONDS)
    lastFrameTime = now
    if (elapsed > 0.0) callbacks.onFrame(1.0 / elapsed)
  }

  // endregion

  // region events

  private fun wireEvents(map: MaplibreMap) {
    map.subscribe("style.load") {
      styleLoadPending = false
      styleBinding?.unload()
      val binding = GlJsStyleBinding(map, logger).also { styleBinding = it }
      callbacks.onStyleChanged(this, GlJsStyle(binding) { appliedExtent.scaleFactor.toFloat() })
      applyTileLod(map)
      if (!hasLoadedInitialStyle) {
        hasLoadedInitialStyle = true
        runPending(pendingInitialStyleActions, map)
      }
      reportStyleLoaded = true
      reportLoadedOnceStyleIsReady(map)
    }
    // A source naming a TileJSON fetches it after `style.load`, so its attribution is not readable
    // there.
    map.subscribe("styledata") { reportLoadedOnceStyleIsReady(map) }
    map.subscribe("sourcedata") { event ->
      reportLoadedOnceStyleIsReady(map)
      if (event.sourceDataType == "metadata") {
        applyTileLod(map)
        event.sourceId?.let { callbacks.onSourceChanged(this, it) }
      }
    }
    map.subscribe("error") { event ->
      val reason = event.error?.message ?: "MapLibre failed to load the map"
      if (styleLoadPending) {
        styleLoadPending = false
        logger?.e { "Map loading failed: $reason" }
        callbacks.onMapFailLoading(reason)
        if (!hasLoadedInitialStyle) abandonPending(pendingInitialStyleActions)
      } else {
        // Tile and sprite failures land here too, and are not the map failing to load.
        logger?.w { "MapLibre reported an error: $reason" }
      }
    }

    map.subscribe("movestart") { beginCameraMove() }
    map.subscribe("move") { callbacks.onCameraMoved(this) }
    map.subscribe("moveend") {
      callbacks.onCameraMoved(this)
      // A drag is a stream of jumps, each with its own moveend.
      if (!isGestureInProgress) endCameraMove()
      resumeTransitions()
    }
  }

  private fun reportLoadedOnceStyleIsReady(map: MaplibreMap) {
    if (!reportStyleLoaded || !map.isStyleLoaded()) return
    reportStyleLoaded = false
    callbacks.onMapFinishedLoading(this)
  }

  /** A move spans the gesture rather than the jump, as every other platform reports it. */
  private fun beginCameraMove() {
    val reason =
      if (isGestureInProgress) CameraMoveReason.GESTURE else CameraMoveReason.PROGRAMMATIC
    if (reportedMoveReason == reason) return
    reportedMoveReason = reason
    callbacks.onCameraMoveStarted(this, reason)
  }

  private fun endCameraMove() {
    if (reportedMoveReason == null) return
    reportedMoveReason = null
    callbacks.onCameraMoveEnded(this)
  }

  private var reportedMoveReason: CameraMoveReason? = null

  // endregion

  // region dispatch

  private fun onMap(action: (MaplibreMap) -> Unit) {
    postWhenMapExists(PendingMapAction(action, abandon = {}))
  }

  private fun postWhenMapExists(action: PendingMapAction) {
    if (closed) {
      action.abandon()
      return
    }
    val current = map
    if (current == null) pendingMapActions += action else action.run(current)
  }

  private fun postWhenInitialStyleLoaded(action: PendingMapAction) {
    if (closed) {
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
    styleBinding?.unload()
    requestedStyle = style
    callbacks.onStyleChanged(this, null)
    onMap(::applyRequestedStyle)
  }

  private fun applyRequestedStyle(map: MaplibreMap) {
    val style = requestedStyle ?: return
    if (style == appliedStyle) return
    appliedStyle = style
    styleLoadPending = true
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
      styleLoadPending = false
      val reason = error.message ?: "MapLibre failed to load the map"
      logger?.e(error) { "Map loading failed: $reason" }
      callbacks.onMapFailLoading(reason)
      if (!hasLoadedInitialStyle) abandonPending(pendingInitialStyleActions)
    }
  }

  /** Answers camera reads made before the map exists. */
  private var requestedCamera: CameraPosition? = null

  override fun getCameraPosition(): CameraPosition =
    withMap(requestedCamera ?: CameraPosition()) { map ->
      CameraPosition(
        bearing = map.getBearing(),
        target = map.getCenter().toPosition(),
        tilt = map.getPitch(),
        zoom = map.getZoom(),
        padding = map.getPadding().toPaddingValues(),
      )
    }

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    requestedCamera = cameraPosition
    onMap { map -> map.jumpTo(cameraPosition.toJumpToOptions()) }
  }

  override fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    onMap { map -> map.fitBounds(boundingBox.toLngLatBounds(), fitOptions(bearing, tilt, padding)) }
  }

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    awaitCameraRelease { map ->
      map.flyTo(
        unsafeJso<FlyToOptions> {
          center = finalPosition.target.toLngLat()
          zoom = finalPosition.zoom
          bearing = finalPosition.bearing
          pitch = finalPosition.tilt
          padding = finalPosition.padding.toPaddingOptions(layoutDirection)
          this.duration = duration.inWholeMilliseconds.toDouble()
        }
      )
    }
  }

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) {
    awaitCameraRelease { map ->
      map.fitBounds(
        boundingBox.toLngLatBounds(),
        fitOptions(bearing, tilt, padding).also {
          it.duration = duration.inWholeMilliseconds.toDouble()
        },
      )
    }
  }

  private fun fitOptions(bearing: Double, tilt: Double, padding: PaddingValues): FitBoundsOptions =
    unsafeJso {
      this.bearing = bearing
      pitch = tilt
      this.padding = padding.toPaddingOptions(layoutDirection)
      // flyTo's arc would zoom out and back in on its way to a camera the caller already named.
      linear = true
      duration = 0.0
    }

  override fun setCameraBoundingBox(boundingBox: BoundingBox?) {
    onMap { map -> map.setMaxBounds(boundingBox?.toLngLatBounds()) }
  }

  override fun setMaxZoom(maxZoom: Double) {
    onMap { it.setMaxZoom(maxZoom) }
  }

  override fun setMinZoom(minZoom: Double) {
    onMap { it.setMinZoom(minZoom) }
  }

  override fun setMinPitch(minPitch: Double) {
    onMap { it.setMinPitch(minPitch) }
  }

  override fun setMaxPitch(maxPitch: Double) {
    onMap { it.setMaxPitch(maxPitch) }
  }

  override fun getVisibleBoundingBox(): BoundingBox =
    withMap(BoundingBox(Position(0.0, 0.0), Position(0.0, 0.0))) { it.getBounds().toBoundingBox() }

  override fun getVisibleRegion(): VisibleRegion =
    withMap(
      VisibleRegion(Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0))
    ) { map ->
      val width = appliedExtent.width.toDouble()
      val height = appliedExtent.height.toDouble()
      VisibleRegion(
        farLeft = map.unprojectAt(0.0, 0.0),
        farRight = map.unprojectAt(width, 0.0),
        nearLeft = map.unprojectAt(0.0, height),
        nearRight = map.unprojectAt(width, height),
      )
    }

  override fun getViewport(): Viewport? =
    withMap(null as Viewport?) { map ->
      // GL JS adopts a resize synchronously in applyExtent, so the applied extent, the bounds, and
      // the transform the conversions read all describe the same viewport here.
      val extent = appliedExtent
      if (extent.isEmpty) return@withMap null
      val camera = getCameraPosition()
      Viewport(
        size = DpSize(extent.width.dp, extent.height.dp),
        visibleBoundingBox = getVisibleBoundingBox(),
        visibleRegion = getVisibleRegion(),
        metersPerDpAtTarget = metersPerDpAtLatitude(camera.zoom, camera.target.latitude),
        map = this,
      )
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

  override fun setGestureSettings(value: GestureOptions) {
    // Gestures are implemented in Compose, so the host's input handling reads these.
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

  override fun positionFromScreenLocation(offset: DpOffset): Position =
    withMap(Position(0.0, 0.0)) { map ->
      map.unprojectAt(offset.x.value.toDouble(), offset.y.value.toDouble())
    }

  override fun screenLocationFromPosition(position: Position): DpOffset =
    withMap(DpOffset.Zero) { map -> map.project(position.toLngLat()).toDpOffset() }

  override suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> = query(offset.toPoint(), layerIds, predicate)

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
  private suspend fun awaitCameraRelease(start: (MaplibreMap) -> Unit) =
    suspendCancellableCoroutine { continuation ->
      val pending =
        PendingMapAction(
          run = { current -> startTransitionOnMap(current, start, continuation) },
          abandon = { if (continuation.isActive) continuation.resume(Unit) },
        )
      continuation.invokeOnCancellation {
        if (pendingInitialStyleActions.remove(pending)) return@invokeOnCancellation
        if (transitionWaiters.remove(continuation)) map?.stop()
      }
      postWhenInitialStyleLoaded(pending)
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

  private var isGestureInProgress = false
  private var nextGestureToken = 0L
  private var activeGestureToken: GestureToken? = null

  override fun onGestureStarted(): GestureToken = GestureToken(++nextGestureToken)

  override fun onGestureEnded(token: GestureToken) {
    if (activeGestureToken != token) return
    activeGestureToken = null
    isGestureInProgress = false
    endCameraMove()
  }

  private fun activateGesture(token: GestureToken?) {
    if (token == null) return
    val active = activeGestureToken
    if (active != null && token.value < active.value) return
    activeGestureToken = token
    isGestureInProgress = true
  }

  override fun moveBy(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken?,
  ) {
    activateGesture(gestureToken)
    onMap { map -> map.panBy(panOffset(deltaX, deltaY), animation(duration)) }
  }

  override suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    activateGesture(gestureToken)
    awaitCameraRelease { map -> map.panBy(panOffset(deltaX, deltaY), animation(duration)) }
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
    activateGesture(gestureToken)
    onMap { map -> map.easeTo(zoomOptions(map, scale, anchor, duration)) }
  }

  override suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    activateGesture(gestureToken)
    awaitCameraRelease { map -> map.easeTo(zoomOptions(map, scale, anchor, duration)) }
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
    activateGesture(gestureToken)
    onMap { map -> map.easeTo(rotateOptions(map, bearingDelta, pitchDelta, anchor, duration)) }
  }

  override suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    activateGesture(gestureToken)
    awaitCameraRelease { map ->
      map.easeTo(rotateOptions(map, bearingDelta, pitchDelta, null, duration))
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

  override fun onPrimaryClick(offset: DpOffset) {
    val position = map?.unprojectAt(offset.x.value.toDouble(), offset.y.value.toDouble()) ?: return
    callbacks.onClick(this, position, offset)
  }

  /** A mouse has no press-and-hold convention, so the secondary button is the long press. */
  override fun onSecondaryClick(offset: DpOffset) {
    val position = map?.unprojectAt(offset.x.value.toDouble(), offset.y.value.toDouble()) ?: return
    callbacks.onLongClick(this, position, offset)
  }

  // endregion

  private fun CameraPosition.toJumpToOptions(): JumpToOptions = unsafeJso {
    center = target.toLngLat()
    zoom = this@toJumpToOptions.zoom
    bearing = this@toJumpToOptions.bearing
    pitch = tilt
    padding = this@toJumpToOptions.padding.toPaddingOptions(layoutDirection)
  }

  private fun MaplibreMap.unprojectAt(x: Double, y: Double): Position =
    unproject(
        unsafeJso<Point> {
          this.x = x
          this.y = y
        }
      )
      .toPosition()

  private companion object {
    const val OFFSCREEN_CONTAINER_STYLE =
      "position:absolute;left:-10000px;top:0;visibility:hidden;pointer-events:none;"
  }
}
