package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import js.objects.unsafeJso
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import org.maplibre.compose.gljs.CameraForBoundsOptions
import org.maplibre.compose.gljs.DEFAULT_WORKER_URL
import org.maplibre.compose.gljs.EaseToOptions
import org.maplibre.compose.gljs.FilterSpecification
import org.maplibre.compose.gljs.FlyToOptions
import org.maplibre.compose.gljs.GlJsFrameTarget
import org.maplibre.compose.gljs.GlJsMapRenderer
import org.maplibre.compose.gljs.GlJsRenderTarget
import org.maplibre.compose.gljs.GlJsRuntime
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
import org.maplibre.compose.gljs.queryBox
import org.maplibre.compose.gljs.queryPoint
import org.maplibre.compose.gljs.styleJson
import org.maplibre.compose.gljs.styleUrl
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.style.BaseStyle
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
 * The map can only be built once Compose supplies a WebGL context and a size. Calls before then are
 * queued, and reads answer with the most recently requested values.
 */
internal class GlJsMapSession(
  internal var callbacks: MapAdapter.Callbacks,
  internal var logger: Logger?,
  internal var layoutDirection: LayoutDirection,
) : MapAdapter, GlJsMapRenderer, GestureTarget {

  private var map: MaplibreMap? = null

  /** The live map once the first frame has built it; the public withPlatformMap reads this. */
  internal val liveMap: MaplibreMap?
    get() = map

  /** Runs [block] on the live map, waiting until the first frame constructs it. */
  internal suspend fun <T> withLiveMap(block: (MaplibreMap) -> T): T {
    map?.let {
      return block(it)
    }
    return suspendCancellableCoroutine { continuation ->
      val action =
        PendingAction<MaplibreMap>(
          run = { current ->
            if (continuation.isActive) continuation.resumeWith(runCatching { block(current) })
          },
          abandon = {
            if (continuation.isActive) {
              continuation.resumeWithException(
                IllegalStateException(
                  "MapState has no live map while detached; on Web the map exists only while a " +
                    "MaplibreMap is composed"
                )
              )
            }
          },
        )
      postWhenMapExists(action)
      continuation.invokeOnCancellation { pendingMapActions.remove(action) }
    }
  }

  /** MapLibre sizes its viewport from a container even when it renders nowhere near one. */
  private var container: HTMLElement? = null

  private var surface: GlJsSurfaceSession? = null
  private var closed = false

  /** True after [close]; the engine and tests read it to observe a state-driven teardown. */
  internal val isClosed: Boolean
    get() = closed

  /** Set by the engine before a MapState.close teardown; a plain detach leaves it false. */
  internal var closingWithState = false

  /** Everything here runs on the one browser thread, so the queues take no lock. */
  private val runOnMap: (MaplibreMap, PendingAction<MaplibreMap>) -> Boolean = { map, action ->
    action.run(map)
    true
  }

  /** Actions accepted before Compose supplies the context used to construct the map. */
  private val pendingMapActions = PendingActionQueue(dispatch = runOnMap)

  /** Transitions MapLibre would cancel while applying the first style's camera. */
  private val pendingInitialStyleActions = PendingActionQueue(dispatch = runOnMap)

  /** Readiness belongs to a map instance, not to its current, replaceable style. */
  private var hasLoadedInitialStyle = false

  /**
   * True once this session has loaded a style. The surface presents no frame while this is false,
   * and it stays true so a later style switch does not blank a live map.
   */
  internal var hasLoadedFirstStyle by mutableStateOf(false)
    private set

  private val styleState = RequestedStyleState()

  /** Set while a style load is outstanding, so an `error` can be told from a tile failure. */
  private var styleLoadPending = false

  private var styleBinding: GlJsStyleBinding? = null

  private var reportStyleLoaded = false

  private var appliedExtent: MapExtent = MapExtent.Empty

  private var framebuffer: Any? = null

  private var lentContext: WebGL2RenderingContext? = null

  private var maximumFps: Int? = null
  private var cameraConstraints: CameraConstraints? = null
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

    map = created
    hasLoadedInitialStyle = false
    appliedExtent = MapExtent.Empty
    applyRequestedStyle(created)
    cameraConstraints?.let { applyCameraConstraints(created, it) }
    // The destroyed map took its padding and camera with it; the saved values apply here.
    created.jumpTo(unsafeJso<JumpToOptions> { this.padding = cameraPadding })
    requestedCamera?.let { created.jumpTo(it.toJumpToOptions()) }
    pendingMapActions.flush { PendingActionGate.Open(created) }
    return created
  }

  private fun destroyMap() {
    hasLoadedInitialStyle = false
    // The replacement map loads its first style from scratch; presenting before then shows blank
    // frames instead of the load placeholder.
    hasLoadedFirstStyle = false
    endCameraMove()
    val current = map ?: return
    // The replacement map starts where this one left off, not at the last requested position.
    runCatching { requestedCamera = current.cameraPosition() }
    map = null
    styleBinding?.unload()
    styleBinding = null
    styleState.resetApplied()
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
    // No `moveend` follows a map that is going away.
    resumeTransitions()
    // The state must not keep describing the destroyed map's style, sources, and viewport.
    callbacks.onMapDestroyed(this)
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
      hasLoadedFirstStyle = true
      styleBinding?.unload()
      val binding =
        GlJsStyleBinding(map, logger) { appliedExtent.scaleFactor.toFloat() }
          .also { styleBinding = it }
      callbacks.onStyleChanged(this, binding, styleState.appliedGeneration)
      applyTileLod(map)
      if (!hasLoadedInitialStyle) {
        hasLoadedInitialStyle = true
        pendingInitialStyleActions.flush { PendingActionGate.Open(map) }
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
        callbacks.onMapFailLoading(reason, styleState.requestedGeneration)
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
    callbacks.onMapFinishedLoading(this, styleState.appliedGeneration)
  }

  /** A move spans the gesture rather than the jump, as every other platform reports it. */
  private val moveReporter =
    CameraMoveReporter(
      moveReason = {
        if (isGestureInProgress) CameraMoveReason.GESTURE else CameraMoveReason.PROGRAMMATIC
      },
      onStarted = { callbacks.onCameraMoveStarted(this, it) },
      onEnded = { callbacks.onCameraMoveEnded(this) },
    )

  private fun beginCameraMove() = moveReporter.begin()

  private fun endCameraMove() = moveReporter.end()

  // endregion

  // region dispatch

  private fun onMap(action: (MaplibreMap) -> Unit) {
    postWhenMapExists(PendingAction(action))
  }

  private fun postWhenMapExists(action: PendingAction<MaplibreMap>) {
    val accepted =
      pendingMapActions.post(action) {
        when {
          closed -> PendingActionGate.Refused
          else -> map?.let { PendingActionGate.Open(it) } ?: PendingActionGate.Held
        }
      }
    if (!accepted) action.abandon()
  }

  private fun postWhenInitialStyleLoaded(action: PendingAction<MaplibreMap>) {
    val accepted =
      pendingInitialStyleActions.post(action) {
        val current = map
        when {
          closed -> PendingActionGate.Refused
          current == null || !hasLoadedInitialStyle -> PendingActionGate.Held
          else -> PendingActionGate.Open(current)
        }
      }
    if (!accepted) action.abandon()
  }

  private fun abandonPending(actions: PendingActionQueue<MaplibreMap, MaplibreMap>) {
    actions.drain().forEach { it.abandon() }
  }

  private fun <T> withMap(fallback: T, action: (MaplibreMap) -> T): T = map?.let(action) ?: fallback

  // endregion

  // region MapAdapter

  override fun setBaseStyle(style: BaseStyle, generation: Long) {
    styleState.request(
      style,
      generation = generation,
      unloadBinding = { styleBinding?.unload() },
      clearStyle = { callbacks.onStyleChanged(this, null, styleState.requestedGeneration) },
      postApply = { onMap(::applyRequestedStyle) },
    )
  }

  private fun applyRequestedStyle(map: MaplibreMap) {
    val request = styleState.takeUnapplied() ?: return
    // Marked before the try so a throw below is not retried against the same map.
    styleState.markApplied(request)
    styleLoadPending = true
    // MapLibre diffs by default, keeping the same Style object, so no `style.load` would fire.
    val options = unsafeJso<SetStyleOptions> { diff = false }
    try {
      when (val style = request.style) {
        is BaseStyle.Uri -> map.setStyle(styleUrl(style.uri), options)
        is BaseStyle.Json -> map.setStyle(styleJson(style.json), options)
      }
    } catch (error: Throwable) {
      // An inline style is parsed here rather than fetched, so a malformed one throws where every
      // other load failure arrives as an `error` event.
      styleLoadPending = false
      val reason = error.message ?: "MapLibre failed to load the map"
      logger?.e(error) { "Map loading failed: $reason" }
      callbacks.onMapFailLoading(reason, styleState.requestedGeneration)
      if (!hasLoadedInitialStyle) abandonPending(pendingInitialStyleActions)
    }
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

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    requestedCamera = cameraPosition
    onMap { map -> map.jumpTo(cameraPosition.toJumpToOptions()) }
  }

  override fun setCameraPadding(padding: PaddingValues) {
    val resolved = padding.toPaddingOptions(layoutDirection)
    if (cameraPadding.sameAs(resolved)) return
    cameraPadding = resolved
    onMap { map -> map.jumpTo(unsafeJso<JumpToOptions> { this.padding = resolved }) }
  }

  override suspend fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ): Unit = suspendCancellableCoroutine { continuation ->
    postWhenMapExists(
      PendingAction(
        run = { map ->
          if (continuation.isActive) {
            continuation.resumeWith(
              runCatching {
                map.cameraPositionForBounds(boundingBox, bearing, tilt, padding)?.let {
                  map.jumpTo(it.toJumpToOptions())
                }
                Unit
              }
            )
          }
        },
        // The fit ends with the session that accepted it; a state close fails it instead.
        abandon = {
          if (continuation.isActive) {
            if (closingWithState) {
              continuation.resumeWithException(
                IllegalStateException("MapState was closed before the camera fit ran")
              )
            } else {
              continuation.resume(Unit)
            }
          }
        },
      )
    )
  }

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    awaitCameraRelease { map ->
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

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) {
    awaitCameraRelease { map ->
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

  private fun getVisibleBoundingBox(): BoundingBox =
    withMap(BoundingBox(Position(0.0, 0.0), Position(0.0, 0.0))) { it.getBounds().toBoundingBox() }

  private fun getVisibleRegion(): VisibleRegion =
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
        PendingAction<MaplibreMap>(
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
    resumeStranded(resuming)
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
