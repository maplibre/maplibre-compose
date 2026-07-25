package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.desktop.DesktopFrameResult
import org.maplibre.compose.desktop.DesktopMapExtent
import org.maplibre.compose.desktop.DesktopMapFrame
import org.maplibre.compose.desktop.DesktopMapHostSession
import org.maplibre.compose.desktop.DesktopMapRenderer
import org.maplibre.compose.desktop.DesktopRenderTarget
import org.maplibre.compose.desktop.EglContextHandles
import org.maplibre.compose.desktop.MapRenderBackend
import org.maplibre.compose.desktop.MetalTextureTarget
import org.maplibre.compose.desktop.OpenGlTextureTarget
import org.maplibre.compose.desktop.VulkanImageTarget
import org.maplibre.compose.desktop.WglContextHandles
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.resource.DesktopResourceProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesktopStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toCameraOptions
import org.maplibre.compose.util.toCameraPosition
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toEdgeInsets
import org.maplibre.compose.util.toLatLng
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toScreenPoint
import org.maplibre.nativeffi.camera.AnimationOptions
import org.maplibre.nativeffi.camera.BoundOptions
import org.maplibre.nativeffi.camera.CameraFitOptions
import org.maplibre.nativeffi.error.InvalidStateException
import org.maplibre.nativeffi.geo.LatLng
import org.maplibre.nativeffi.geo.LatLngBounds
import org.maplibre.nativeffi.geo.ScreenPoint
import org.maplibre.nativeffi.map.DebugOption
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapOptions
import org.maplibre.nativeffi.render.MetalBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.OpenGLBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent
import org.maplibre.nativeffi.render.VulkanBorrowedTextureDescriptor
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** Native diagnostic MapLibre reports when a render was requested with nothing to draw. */
private const val NO_RENDER_UPDATE_DIAGNOSTIC = "no map render update is available"

/** MapLibre projects with 512px tiles; the meters-per-pixel fallback depends on it. */
private const val TILE_SIZE = 512.0

private const val EARTH_CIRCUMFERENCE_METERS = 2.0 * PI * 6378137.0

/** Latitude beyond which Web Mercator is undefined. */
private const val MERCATOR_MAX_LATITUDE = 85.051129

/**
 * Drives one MapLibre Native map on a desktop host surface.
 *
 * Owns the whole native chain — runtime, map, render session — on a single dedicated owner thread,
 * and exposes it to Compose through [MapAdapter]. Nothing native is created eagerly: the runtime is
 * bound to whichever thread creates it, so everything is built lazily inside [render], which the
 * host already calls on the owner thread.
 */
internal class DesktopMapSession(
  internal var callbacks: MapAdapter.Callbacks,
  internal var logger: Logger?,
  renderBackend: MapRenderBackend,
  private val layoutDirection: LayoutDirection,
) : MapAdapter, DesktopMapRenderer {

  override val backend: MapRenderBackend = renderBackend

  /**
   * Dispatches native work onto the host's renderer thread.
   *
   * The session deliberately does not own a thread. MapLibre binds its runtime to the thread that
   * creates it, and the host already calls [render] on its own renderer thread, so that thread must
   * be the owner. Running a second thread here would bind the runtime somewhere the host never
   * calls from, and every frame would fail with a wrong-thread error.
   */
  private val owner = OwnerDispatch()

  private inner class OwnerDispatch {
    /** The host thread the runtime is bound to, recorded on the first frame. */
    var thread: Thread? = null

    fun <T> run(action: () -> T): T {
      // With no host yet there is no map either, so every caller's own null-guard makes running
      // inline a no-op rather than a wrong-thread hazard.
      val host = hostSession ?: return action()
      return host.withRendererAccess(action)
    }

    fun assertOwnerThread(operation: String) {
      val expected = thread ?: return
      check(Thread.currentThread() === expected) {
        "$operation must run on the map's owner thread (${expected.name}), but ran on " +
          "${Thread.currentThread().name}. MapLibre enforces this natively, so the failure would " +
          "otherwise surface as a WrongThreadException at the FFI boundary."
      }
    }
  }

  // Strong references, deliberately: RuntimeEvent.mapSource resolves through a WeakReference, so a
  // collected MapHandle would make every map event unattributable.
  private var runtime: RuntimeHandle? = null
  private var map: MapHandle? = null
  private var renderSession: RenderSessionHandle? = null

  private var hostSession: DesktopMapHostSession? = null

  /** Identifies the target a render session is attached to; a change forces a re-attach. */
  private data class TargetKey(val generation: Long, val extent: DesktopMapExtent)

  private var attachedTarget: TargetKey? = null

  /** The map's logical size, tracked because MapHandle exposes no size accessor. */
  private var mapExtent: DesktopMapExtent = DesktopMapExtent.Empty

  /**
   * The density the map was created with.
   *
   * MapLibre fixes pixelRatio at creation, so a change here means recreating the map rather than
   * resizing it.
   */
  private var mapScaleFactor: Double = 0.0

  private var renderPending = false
  private var hasRenderedAFrame = false
  private var pendingStyle: BaseStyle? = null
  private var appliedStyle: BaseStyle? = null
  private var closed = false

  /**
   * Distinguishes a completed camera animation from a superseded one.
   *
   * MapLibre fires MAP_CAMERA_DID_CHANGE identically for a jump, a finished ease, a cancellation,
   * and a transition replaced by a newer one, so the event alone cannot resolve a continuation.
   */
  private var cameraGeneration = 0L

  private var isGestureInProgress = false

  /** The binding handed to the current style's sources and layers; replaced on every style load. */
  private var styleBinding: SessionStyleBinding? = null

  /**
   * Routes a style's descriptors to this session's map, on its owner thread.
   *
   * Held by every source and layer in the style, so it has to keep working for as long as they do
   * and then stop cleanly: once [unload] runs, writes are dropped instead of reaching a map whose
   * style has been replaced.
   */
  private inner class SessionStyleBinding : StyleBinding {
    private var loaded = true

    override val isLoaded: Boolean
      get() = loaded && !closed

    fun unload() {
      loaded = false
    }

    override fun <T> withMap(action: (MapHandle) -> T): T? =
      if (!isLoaded) null else owner.run { map?.let(action) }
  }

  private var maximumFps: Int? = null
  private var lastRenderTime = TimeSource.Monotonic.markNow()

  private val frameTimer = TimeSource.Monotonic
  private var lastFrameTime = frameTimer.markNow()

  // ───────────────────────────── host surface lifecycle ─────────────────────────────

  override fun onSurfaceAvailable(session: DesktopMapHostSession) {
    hostSession = session
  }

  override fun onSurfaceChanged(extent: DesktopMapExtent) {
    // The map is resized as part of attaching the new target; see ensureAttached.
    requestRender()
  }

  override fun onSurfaceLost() {
    // The host is about to free the target these handles point at, so the render session must go
    // first. The map and runtime outlive surface loss and are reused if a surface returns.
    runCatching { owner.run { closeRenderSession() } }
      .onFailure { logger?.e(it) { "Failed to close the render session on surface loss" } }
    hostSession = null
  }

  override fun render(frame: DesktopMapFrame): DesktopFrameResult {
    owner.thread = owner.thread ?: Thread.currentThread()
    owner.assertOwnerThread("DesktopMapSession.render")
    if (closed) return DesktopFrameResult.SKIPPED

    val runtime = ensureRuntime()
    val map = ensureMap(frame.extent)

    // Pump unconditionally, render conditionally. Native progress — style parsing, tile loads,
    // camera transitions — only happens while the runtime is pumped.
    runtime.runOnce()
    drainEvents(runtime)

    applyPendingStyle(map)

    if (!ensureAttached(map, frame)) return DesktopFrameResult.SKIPPED
    if (!renderPending) return DesktopFrameResult.SKIPPED
    if (!allowRenderNow()) {
      // Throttled rather than dropped: ask for another frame so the update is not lost.
      requestRender()
      return DesktopFrameResult.SKIPPED
    }

    val session = renderSession ?: return DesktopFrameResult.SKIPPED
    return try {
      session.renderUpdate()
      if (!hasRenderedAFrame) {
        hasRenderedAFrame = true
        // A blank desktop map is the failure mode with the least to go on, so record the moment
        // the first frame actually reaches the GPU. Its absence is the single most useful signal.
        logger?.i { "Rendered the first map frame with $backend on ${Thread.currentThread().name}" }
      }
      renderPending = false
      lastRenderTime = TimeSource.Monotonic.markNow()
      reportFrameRate()
      DesktopFrameResult.RENDERED
    } catch (error: InvalidStateException) {
      if (error.diagnostic == NO_RENDER_UPDATE_DIAGNOSTIC) {
        // Expected before the style produces its first update. Not an application error, and the
        // pending bit stays set so the next frame retries.
        DesktopFrameResult.SKIPPED
      } else {
        throw error
      }
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    try {
      owner.run {
        // Order is mandatory and enforced natively: a handle that still has live children refuses
        // to close, and a failed close leaves it live rather than half-torn-down. Each step gets
        // its own finally so a failure early on cannot strand the rest.
        try {
          closeRenderSession()
        } finally {
          try {
            runCatching { map?.close() }
              .onFailure { logger?.e(it) { "Failed to close the MapLibre map" } }
            map = null
          } finally {
            runCatching { runtime?.close() }
              .onFailure { logger?.e(it) { "Failed to close the MapLibre runtime" } }
            runtime = null
          }
        }
      }
    } finally {
      hostSession = null
    }
  }

  private fun closeRenderSession() {
    owner.assertOwnerThread("closeRenderSession")
    runCatching { renderSession?.close() }
      .onFailure { logger?.e(it) { "Failed to close the MapLibre render session" } }
    renderSession = null
    attachedTarget = null
  }

  // ───────────────────────────── native construction ─────────────────────────────

  private fun ensureRuntime(): RuntimeHandle =
    runtime
      ?: RuntimeHandle.create(RuntimeOptions()).also {
        runtime = it
        // Must precede map creation: MapLibre refuses to replace a resource provider once the
        // runtime owns maps, and there is no way to clear one.
        it.setResourceProvider(DesktopResourceProvider(logger))
        logger?.i { "Created MapLibre runtime on ${Thread.currentThread().name}" }
      }

  private fun ensureMap(extent: DesktopMapExtent): MapHandle {
    val existing = map
    if (existing != null && mapScaleFactor == extent.scaleFactor) return existing

    if (existing != null) {
      // pixelRatio is fixed at creation, so a density change cannot be applied by resizing; the
      // map has to be rebuilt or tile selection and symbol density stay at the old density.
      logger?.i {
        "Display scale changed from $mapScaleFactor to ${extent.scaleFactor}; recreating the map"
      }
      closeRenderSession()
      runCatching { existing.close() }
        .onFailure { logger?.e(it) { "Failed to close the map while changing display scale" } }
      map = null
      appliedStyle = null
      pendingStyle = pendingStyle ?: appliedStyle
    }

    val options =
      MapOptions().also {
        it.width = extent.width.coerceAtLeast(1)
        it.height = extent.height.coerceAtLeast(1)
        it.scaleFactor = extent.scaleFactor
      }

    return MapHandle.create(ensureRuntime(), options).also {
      map = it
      mapExtent = extent
      mapScaleFactor = extent.scaleFactor
      renderPending = true
    }
  }

  /** Attaches or re-attaches the render session, returning whether one is usable. */
  private fun ensureAttached(map: MapHandle, frame: DesktopMapFrame): Boolean {
    val extent = frame.extent
    if (extent.isEmpty) return false

    val key = TargetKey(frame.target.generation, extent)
    if (attachedTarget == key && renderSession != null) return true

    // Borrowed-texture sessions cannot be resized: the only way to follow the host's target is to
    // close the old session and attach a new one. Attaching before closing throws, because a map
    // permits only one live session.
    closeRenderSession()

    // No map.resize exists, and none is needed: attaching sets the map size from the
    // descriptor's logical extent.
    mapExtent = extent

    renderSession =
      try {
        attachBorrowedTexture(map, frame.target, extent)
      } catch (error: Throwable) {
        logger?.e(error) { "Failed to attach a render session to the host target" }
        throw error
      }
    attachedTarget = key
    renderPending = true
    return true
  }

  private fun attachBorrowedTexture(
    map: MapHandle,
    target: DesktopRenderTarget,
    extent: DesktopMapExtent,
  ): RenderSessionHandle {
    // RenderTargetExtent is logical; the host allocated the texture at the matching physical size.
    val ffiExtent =
      RenderTargetExtent(
        width = extent.width.coerceAtLeast(1),
        height = extent.height.coerceAtLeast(1),
        scaleFactor = extent.scaleFactor,
      )

    return when (target) {
      is VulkanImageTarget ->
        map.attachVulkanBorrowedTexture(
          VulkanBorrowedTextureDescriptor(
              extent = ffiExtent,
              context = target.context.toFfi(),
              image = NativePointer.ofAddress(target.image.address),
              imageView = NativePointer.ofAddress(target.imageView.address),
              format = target.format,
              initialLayout = target.initialLayout,
            )
            .also { it.finalLayout = target.finalLayout }
        )

      is MetalTextureTarget ->
        map.attachMetalBorrowedTexture(
          MetalBorrowedTextureDescriptor(
            extent = ffiExtent,
            texture = NativePointer.ofAddress(target.texture.address),
          )
        )

      is OpenGlTextureTarget -> {
        target.makeContextCurrent()
        map.attachOpenGLBorrowedTexture(
          OpenGLBorrowedTextureDescriptor(
            extent = ffiExtent,
            context =
              when (val context = target.context) {
                is EglContextHandles -> context.toFfi()
                is WglContextHandles -> context.toFfi()
              },
            texture = target.textureName,
            target = target.textureTarget,
          )
        )
      }
    }
  }

  // ───────────────────────────── event pump ─────────────────────────────

  private fun drainEvents(runtime: RuntimeHandle) {
    val map = this.map
    while (true) {
      val event =
        try {
          runtime.pollEvent() ?: break
        } catch (error: Throwable) {
          // pollEvent is not a pure read; on MAP_STYLE_LOADED it calls into the map, so it can
          // throw from the map rather than the runtime.
          logger?.e(error) { "Failed to poll a MapLibre runtime event" }
          break
        }
      if (map != null && event.mapSource != null && event.mapSource !== map) continue
      handleEvent(event)
    }
  }

  private fun handleEvent(event: RuntimeEvent) {
    when (event.type) {
      RuntimeEventType.MAP_RENDER_UPDATE_AVAILABLE -> {
        renderPending = true
        requestRender()
      }

      RuntimeEventType.MAP_RENDER_FRAME_FINISHED -> {
        val payload = event.payload
        if (payload is RuntimeEventPayload.RenderFrame) {
          if (payload.needsRepaint) {
            renderPending = true
            requestRender()
          }
        }
      }

      RuntimeEventType.MAP_STYLE_LOADED -> {
        // A new style replaces every source and layer, so the previous binding is dead. Marking it
        // unloaded is what makes descriptors that outlive it degrade rather than write into a
        // style that no longer exists.
        styleBinding?.unload()
        val binding = SessionStyleBinding().also { styleBinding = it }
        callbacks.onStyleChanged(this, DesktopStyle(binding))
      }

      RuntimeEventType.MAP_LOADING_FINISHED -> callbacks.onMapFinishedLoading(this)

      RuntimeEventType.MAP_LOADING_FAILED -> {
        // Style load failures arrive only as events, never as exceptions from the style setters.
        val reason = event.message.ifBlank { "MapLibre failed to load the map" }
        logger?.e { "Map loading failed (code ${event.code}): $reason" }
        callbacks.onMapFailLoading(reason)
      }

      RuntimeEventType.MAP_CAMERA_WILL_CHANGE ->
        callbacks.onCameraMoveStarted(
          this,
          if (isGestureInProgress) CameraMoveReason.GESTURE else CameraMoveReason.PROGRAMMATIC,
        )

      RuntimeEventType.MAP_CAMERA_IS_CHANGING -> callbacks.onCameraMoved(this)

      RuntimeEventType.MAP_CAMERA_DID_CHANGE -> {
        callbacks.onCameraMoved(this)
        callbacks.onCameraMoveEnded(this)
      }

      RuntimeEventType.MAP_RENDER_ERROR ->
        logger?.e { "MapLibre render error: ${event.message.ifBlank { "unknown" }}" }

      RuntimeEventType.MAP_STYLE_IMAGE_MISSING ->
        // TODO(step 6): expose a style-image callback hook so applications can supply the image.
        logger?.d { "Style image missing: ${event.message}" }

      // Known, and deliberately not acted on. Named so that the branch below means "an event
      // this build has never seen" rather than "anything we happen not to use".
      RuntimeEventType.MAP_LOADING_STARTED,
      RuntimeEventType.MAP_IDLE,
      RuntimeEventType.MAP_RENDER_FRAME_STARTED,
      RuntimeEventType.MAP_RENDER_MAP_STARTED,
      RuntimeEventType.MAP_RENDER_MAP_FINISHED,
      RuntimeEventType.MAP_STILL_IMAGE_FINISHED,
      RuntimeEventType.MAP_STILL_IMAGE_FAILED,
      RuntimeEventType.MAP_TILE_ACTION -> Unit

      else ->
        // Event types are value classes over Int, not enums, so an FFI upgrade can introduce a
        // type this build has never seen. Logging beats failing.
        logger?.v { "Unrecognized MapLibre event type ${event.type}" }
    }
  }

  private fun requestRender() {
    hostSession?.requestFrame()
  }

  private fun allowRenderNow(): Boolean {
    val fps = maximumFps ?: return true
    if (fps <= 0) return true
    val minimumInterval = 1.0 / fps
    return lastRenderTime.elapsedNow().toDouble(DurationUnit.SECONDS) >= minimumInterval
  }

  private fun reportFrameRate() {
    val now = frameTimer.markNow()
    val elapsed = (now - lastFrameTime).toDouble(DurationUnit.SECONDS)
    lastFrameTime = now
    if (elapsed > 0.0) callbacks.onFrame(1.0 / elapsed)
  }

  // ───────────────────────────── MapAdapter ─────────────────────────────

  /** Runs [action] with the map on the owner thread, or returns [fallback] if there is none yet. */
  private fun <T> withMap(fallback: T, action: (MapHandle) -> T): T = owner.run {
    map?.let(action) ?: fallback
  }

  private fun requireMap(operation: String): Nothing =
    error("$operation requires a live map; the desktop map has not been created yet")

  override fun setBaseStyle(style: BaseStyle) {
    if (style == appliedStyle && style == pendingStyle) return
    pendingStyle = style
    requestRender()
  }

  private fun applyPendingStyle(map: MapHandle) {
    val style = pendingStyle ?: return
    if (style == appliedStyle) {
      pendingStyle = null
      return
    }
    when (style) {
      is BaseStyle.Uri -> map.setStyleUrl(style.uri)
      is BaseStyle.Json -> map.setStyleJson(style.json)
    }
    appliedStyle = style
    pendingStyle = null
    renderPending = true
  }

  override fun getCameraPosition(): CameraPosition =
    withMap(CameraPosition()) { it.camera.toCameraPosition() }

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    owner.run {
      val map = map ?: return@run
      cameraGeneration++
      map.jumpTo(cameraPosition.toCameraOptions(layoutDirection))
      renderPending = true
    }
    requestRender()
  }

  override fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    owner.run {
      val map = map ?: return@run
      cameraGeneration++
      map.jumpTo(cameraForBounds(map, boundingBox, bearing, tilt, padding))
      renderPending = true
    }
    requestRender()
  }

  private fun cameraForBounds(
    map: MapHandle,
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) =
    map.cameraForLatLngBounds(
      bounds = boundingBox.toLatLngBounds(),
      // Passing the padding through fitOptions rather than accepting the returned zero insets,
      // which would silently clear whatever padding the caller configured.
      fitOptions =
        CameraFitOptions().also {
          it.padding = padding.toEdgeInsets(layoutDirection)
          it.bearing = bearing
          it.pitch = tilt
        },
    )

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    animate(duration) { map ->
      map.flyTo(
        finalPosition.toCameraOptions(layoutDirection),
        AnimationOptions().also { it.durationMs = duration.inWholeMilliseconds.toDouble() },
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
    animate(duration) { map ->
      map.flyTo(
        cameraForBounds(map, boundingBox, bearing, tilt, padding),
        AnimationOptions().also { it.durationMs = duration.inWholeMilliseconds.toDouble() },
      )
    }
  }

  /**
   * Starts a camera transition and waits out its duration.
   *
   * There is no completion signal in the FFI, so the wait is by duration and a generation stamp
   * guards against a superseding transition resolving this one.
   */
  private suspend fun animate(duration: Duration, start: (MapHandle) -> Unit) {
    val generation =
      owner.run {
        val map = map ?: return@run null
        cameraGeneration++
        start(map)
        renderPending = true
        cameraGeneration
      } ?: return
    requestRender()

    try {
      delay(duration)
    } catch (cancellation: CancellationException) {
      owner.run { if (cameraGeneration == generation) map?.cancelTransitions() }
      throw cancellation
    }
  }

  override fun setCameraBoundingBox(boundingBox: BoundingBox?) {
    owner.run {
      val map = map ?: return@run
      map.bounds =
        map.bounds.also {
          // An all-null BoundOptions is a no-op rather than a reset, so clearing means assigning
          // the whole world explicitly.
          it.bounds =
            boundingBox?.toLatLngBounds()
              ?: LatLngBounds(LatLng(-90.0, -180.0), LatLng(90.0, 180.0))
        }
    }
  }

  override fun setMaxZoom(maxZoom: Double) = setBounds { it.maxZoom = maxZoom }

  override fun setMinZoom(minZoom: Double) = setBounds { it.minZoom = minZoom }

  override fun setMinPitch(minPitch: Double) = setBounds { it.minPitch = minPitch }

  override fun setMaxPitch(maxPitch: Double) = setBounds { it.maxPitch = maxPitch }

  private fun setBounds(update: (BoundOptions) -> Unit) {
    owner.run {
      val map = map ?: return@run
      map.bounds = map.bounds.also(update)
    }
  }

  override fun getVisibleBoundingBox(): BoundingBox =
    withMap(BoundingBox(Position(0.0, 0.0), Position(0.0, 0.0))) {
      it.latLngBoundsForCamera(it.camera).toBoundingBox()
    }

  override fun getVisibleRegion(): VisibleRegion =
    withMap(
      VisibleRegion(Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0))
    ) { map ->
      // TODO(maplibre-native-ffi): Use a native visible-region query once the C API exposes one.
      // latLngBoundsForCamera is axis-aligned, so it is wrong for a rotated or pitched camera;
      // projecting the four viewport corners is correct for both.
      val width = mapExtent.width.toDouble()
      val height = mapExtent.height.toDouble()
      val corners =
        map.latLngsForPixels(
          listOf(
            ScreenPoint(0.0, 0.0),
            ScreenPoint(width, 0.0),
            ScreenPoint(0.0, height),
            ScreenPoint(width, height),
          )
        )
      VisibleRegion(
        farLeft = corners[0].toPosition(),
        farRight = corners[1].toPosition(),
        nearLeft = corners[2].toPosition(),
        nearRight = corners[3].toPosition(),
      )
    }

  override fun setRenderSettings(value: RenderOptions) {
    // TODO(maplibre-native-ffi): Forward maximumFps once the C API exposes frame-rate control.
    // Until then it throttles how often renderUpdate is called rather than pacing MapLibre itself.
    maximumFps = value.maximumFps
    owner.run {
      val map = map ?: return@run
      map.debugOptions = buildSet {
        if (value.isTileBordersEnabled) add(DebugOption.TILE_BORDERS)
        if (value.isTileTimestampsEnabled) add(DebugOption.TIMESTAMPS)
        if (value.isCollisionBoxesEnabled) add(DebugOption.COLLISION)
        if (value.isTileParseStatusEnabled) add(DebugOption.PARSE_STATUS)
      }
    }
  }

  override fun setOrnamentSettings(value: OrnamentOptions) {
    // Desktop draws no ornaments; MapLibre Native has no compass, scale bar, or attribution widget
    // outside the mobile SDKs, so there is nothing to forward.
  }

  override fun setGestureSettings(value: GestureOptions) {
    // Gestures are implemented in Compose rather than by MapLibre Native, so the options are read
    // by the input handling in DesktopMapView rather than pushed into the map.
  }

  override fun positionFromScreenLocation(offset: DpOffset): Position =
    withMap(Position(0.0, 0.0)) { it.latLngForPixel(offset.toScreenPoint()).toPosition() }

  override fun screenLocationFromPosition(position: Position): DpOffset =
    withMap(DpOffset.Zero) { it.pixelForLatLng(position.toLatLng()).toDpOffset() }

  override fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> {
    // TODO(step 6): implement with RenderSessionHandle.queryRenderedFeatures once the FFI feature
    // and expression conversions exist.
    logger?.w { "queryRenderedFeatures is not implemented on desktop yet" }
    return emptyList()
  }

  override fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> {
    // TODO(step 6): as above.
    logger?.w { "queryRenderedFeatures is not implemented on desktop yet" }
    return emptyList()
  }

  override fun metersPerDpAtLatitude(latitude: Double): Double {
    // TODO(maplibre-native-ffi): Use a native meters-per-pixel query once the C API exposes one.
    // This is mbgl's own formula; note the 512px tile size rather than the more common 256.
    val zoom = getCameraPosition().zoom
    val clamped = latitude.coerceIn(-MERCATOR_MAX_LATITUDE, MERCATOR_MAX_LATITUDE)
    return cos(clamped * PI / 180.0) * EARTH_CIRCUMFERENCE_METERS / (2.0.pow(zoom) * TILE_SIZE)
  }

  // ───────────────────────────── input, called from Compose ─────────────────────────────

  fun onGestureStarted() {
    isGestureInProgress = true
  }

  fun onGestureEnded() {
    isGestureInProgress = false
  }

  fun moveBy(deltaX: Double, deltaY: Double) {
    owner.run {
      map?.moveBy(deltaX, deltaY)
      renderPending = true
    }
    requestRender()
  }

  fun scaleBy(scale: Double, anchor: DpOffset?) {
    owner.run {
      map?.scaleBy(scale, anchor?.toScreenPoint())
      renderPending = true
    }
    requestRender()
  }

  fun rotateBy(from: DpOffset, to: DpOffset) {
    owner.run {
      map?.rotateBy(from.toScreenPoint(), to.toScreenPoint())
      renderPending = true
    }
    requestRender()
  }

  fun pitchBy(delta: Double) {
    owner.run {
      map?.pitchBy(delta)
      renderPending = true
    }
    requestRender()
  }

  fun cancelTransitions() {
    owner.run {
      cameraGeneration++
      map?.cancelTransitions()
    }
  }
}

private fun org.maplibre.compose.desktop.VulkanContextHandles.toFfi() =
  org.maplibre.nativeffi.render.VulkanContextDescriptor(
    instance = NativePointer.ofAddress(instance.address),
    physicalDevice = NativePointer.ofAddress(physicalDevice.address),
    device = NativePointer.ofAddress(device.address),
    graphicsQueue = NativePointer.ofAddress(graphicsQueue.address),
    graphicsQueueFamilyIndex = graphicsQueueFamilyIndex,
    getInstanceProcAddr = NativePointer.ofAddress(getInstanceProcAddr.address),
    getDeviceProcAddr = NativePointer.ofAddress(getDeviceProcAddr.address),
  )

private fun EglContextHandles.toFfi() =
  org.maplibre.nativeffi.render.EglContextDescriptor(
    display = NativePointer.ofAddress(display.address),
    config = NativePointer.ofAddress(config.address),
    shareContext = NativePointer.ofAddress(shareContext.address),
    getProcAddress = NativePointer.ofAddress(getProcAddress.address),
  )

private fun WglContextHandles.toFfi() =
  org.maplibre.nativeffi.render.WglContextDescriptor(
    deviceContext = NativePointer.ofAddress(deviceContext.address),
    shareContext = NativePointer.ofAddress(shareContext.address),
    getProcAddress = NativePointer.ofAddress(getProcAddress.address),
  )
