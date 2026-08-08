package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiFatalFrameException
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiMapExtent
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiMapRenderer
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.compose.mlnffi.WglContextHandles
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.renderedQueryOptions
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toCameraOptions
import org.maplibre.compose.util.toCameraPosition
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toEdgeInsets
import org.maplibre.compose.util.toGeoJsonFeature
import org.maplibre.compose.util.toLatLng
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.compose.util.toPosition
import org.maplibre.compose.util.toScreenPoint
import org.maplibre.nativeffi.camera.AnimationOptions
import org.maplibre.nativeffi.camera.BoundOptions
import org.maplibre.nativeffi.camera.BoundsConstraint
import org.maplibre.nativeffi.camera.CameraFitOptions
import org.maplibre.nativeffi.camera.CameraOptions
import org.maplibre.nativeffi.error.InvalidArgumentException
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.error.UnsupportedFeatureException
import org.maplibre.nativeffi.geo.ScreenBox
import org.maplibre.nativeffi.geo.ScreenPoint
import org.maplibre.nativeffi.map.DebugOption
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.query.RenderedQueryGeometry
import org.maplibre.nativeffi.render.MetalBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.OpenGLBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent
import org.maplibre.nativeffi.render.VulkanBorrowedTextureDescriptor
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.style.StyleImageInfo
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** MapLibre projects with 512px tiles; the meters-per-pixel fallback depends on it. */
private const val TILE_SIZE = 512.0

private const val EARTH_CIRCUMFERENCE_METERS = 2.0 * PI * 6378137.0

private const val MIN_PITCH_DEGREES = 0.0

/** MapLibre rejects a pitch beyond this, so the drag is clamped rather than throwing. */
private const val MAX_PITCH_DEGREES = 60.0

/**
 * Latitude beyond which Web Mercator is undefined; mbgl's `util::LATITUDE_MAX` to full precision.
 */
private const val MERCATOR_MAX_LATITUDE = 85.051128779806604

/** Zoom bounds mbgl clamps to before projecting; `util::MIN_ZOOM` and `util::MAX_ZOOM`. */
private const val MIN_PROJECTION_ZOOM = 0.0

private const val MAX_PROJECTION_ZOOM = 25.5

/** The fraction of a capped frame interval a frame may arrive early and still be drawn. */
private const val FRAME_INTERVAL_SLACK = 0.1

/** Shared by the position and bounds-fit setters, so the later of the two replaces the earlier. */
private const val CAMERA_KEY = "camera"

/**
 * Drives one MapLibre Native map on a host surface.
 *
 * The runtime and the map belong to [MlnFfiMapRuntimeLoop]'s thread; the render session belongs to
 * the host's renderer thread, which is where [render] runs and so which attached it. Everything
 * that touches the map hops to the loop; everything that touches the render session stays on the
 * renderer thread.
 *
 * Camera transitions still need frames: mbgl steps one from `onDidFinishRenderingFrame` while
 * `transform.inTransition()`. The cycle is self-sustaining once started, since a transition in
 * progress publishes the next render update, which asks for the next frame.
 */
internal class MlnFfiMapSession(
  @Volatile internal var callbacks: MapAdapter.Callbacks,
  internal var logger: Logger?,
  renderBackend: MapRenderBackend,
  private val layoutDirection: LayoutDirection,
  private val runtimeOptions: MlnFfiRuntimeOptions,
) : MapAdapter, MlnFfiMapRenderer {

  override val backend: MapRenderBackend = renderBackend

  /**
   * Guards [loop] and [mapConfiguration] together, so a setting cannot be lost to a racing start.
   */
  private val stateLock = ReentrantLock()

  @Volatile private var loop: MlnFfiMapRuntimeLoop? = null

  /**
   * One-shot map actions accepted before the first render creates a loop. Guarded by [stateLock].
   */
  private class PendingMapAction(val run: (MapHandle) -> Unit, val abandon: () -> Unit)

  private val pendingMapActions = mutableListOf<PendingMapAction>()

  /** Renderer-thread state. The session is this handle's owner, so nothing else may touch it. */
  private var renderSession: RenderSessionHandle? = null

  @Volatile private var hostSession: MlnFfiMapHostSession? = null

  /** Identifies the target a render session is attached to; a change forces a re-attach. */
  private data class TargetKey(val generation: Long, val extent: MlnFfiMapExtent)

  private var attachedTarget: TargetKey? = null

  /**
   * How many render sessions this session has attached, and how many targets it handed to a live
   * one instead. Renderer-thread state, read by tests.
   */
  @Volatile
  internal var attachCount: Int = 0
    private set

  @Volatile
  internal var retargetCount: Int = 0
    private set

  /**
   * Whether a frame is worth drawing. Set by the map's owner thread, and consumed by the renderer
   * thread before it renders, so a request published during a render is not discarded.
   */
  private val renderRequested = AtomicBoolean(true)

  private var hasRenderedAFrame = false

  @Volatile private var closed = false
  private var failureReported = false

  /** Loop-thread state: the style asked for, and the one MapLibre accepted. */
  @Volatile private var requestedStyle: BaseStyle? = null
  private var appliedStyle: BaseStyle? = null

  @Volatile private var isGestureInProgress = false

  /**
   * The reason a camera move was last reported as started, or null if none is outstanding.
   * Owner-thread state.
   */
  private var reportedMoveReason: CameraMoveReason? = null

  /**
   * The map's configuration, keyed so the last value for each setting wins, replayed in insertion
   * order onto every map this session creates. See [configureMap].
   */
  private val mapConfiguration = LinkedHashMap<String, (MapHandle) -> Unit>()

  /** The binding handed to the current style's sources and layers; replaced on every style load. */
  private var styleBinding: SessionStyleBinding? = null

  /**
   * Routes a style's descriptors to this session's map, on its owner thread. Once [unload] runs,
   * writes are dropped instead of reaching a map whose style has been replaced.
   */
  private inner class SessionStyleBinding : StyleBinding {
    @Volatile private var loaded = true

    override val isLoaded: Boolean
      get() = loaded && !closed

    override val logger: Logger?
      get() = this@MlnFfiMapSession.logger

    fun unload() {
      loaded = false
    }

    /**
     * Runs [action] against the map on its owner thread, requesting a repaint: `addSource`,
     * `removeSource`, and `removeImage` notify mbgl of nothing, so they would render stale.
     */
    override fun <T> withMap(action: (MapHandle) -> T): T? {
      if (!isLoaded) return null
      return runOnMap { map -> action(map).also { map.requestRepaint() } }
    }

    override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? {
      if (!isLoaded) return null
      return withRendererAccess {
        val session = renderSession
        if (session == null) {
          logger?.d { "Ignoring a render session call: no session is attached yet" }
          return@withRendererAccess null
        }
        // Deliberately uncaught: past the null check above, anything MapLibre throws is a bug here.
        action(session)
      }
    }
  }

  @Volatile private var maximumFps: Int? = null
  private var lastRenderTime = TimeSource.Monotonic.markNow()

  private val frameTimer = TimeSource.Monotonic
  private var lastFrameTime = frameTimer.markNow()

  // region host surface lifecycle

  override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
    if (closed) {
      logger?.w { "Ignoring a host surface offered to a closed map session" }
      return
    }
    hostSession = session
    // Load-bearing on a surface that returns after loss: an idle map publishes no render update, so
    // without this one request the new surface is never drawn into.
    requestRender()
  }

  override fun onSurfaceChanged(extent: MlnFfiMapExtent) {
    // The map is resized as part of attaching the new target; see ensureAttached.
    requestRender()
  }

  override fun onSurfaceLost() {
    // The host is about to free the target these handles point at, so the render session must go
    // first, and before the host session is dropped — that is the only route to the thread allowed
    // to close the handle. The map and runtime outlive surface loss and are reused.
    logger?.i { "Host surface lost; closing the render session and waiting for a new one" }
    closeRenderSession()
    hostSession = null
  }

  override fun render(frame: MlnFfiMapFrame): MlnFfiFrameResult {
    if (closed || frame.extent.isEmpty) return MlnFfiFrameResult.SKIPPED

    val loop = ensureLoop(frame.extent)
    loop.failure?.let { error ->
      if (!failureReported) {
        failureReported = true
        // The host stops driving frames after a failure, so nothing else would close the render
        // session, and the loop cannot destroy a map that still has one attached.
        close()
        // Fatal so the surface latches instead of rebuilding its render session and retrying into a
        // runtime that cannot come back.
        throw MlnFfiFatalFrameException("The MapLibre map runtime failed", error)
      }
      return MlnFfiFrameResult.SKIPPED
    }

    // Null until the loop has created its map; it asks for a frame when it has.
    val map = loop.map ?: return MlnFfiFrameResult.SKIPPED

    if (!ensureAttached(map, frame)) return MlnFfiFrameResult.SKIPPED
    // Consumed before rendering, so an update the loop publishes during the render below is not
    // discarded along with the one being drawn.
    if (!renderRequested.getAndSet(false)) return MlnFfiFrameResult.SKIPPED
    // Taken before the render and kept, so the cap measures start-to-start. Measuring from the end
    // of the last render is short by that render's duration, which near the display's own rate is
    // enough to reject every second frame.
    val renderStart = TimeSource.Monotonic.markNow()
    if (!allowRenderNow(renderStart)) {
      // Throttled rather than dropped: ask for another frame so the update is not lost.
      requestRender()
      return MlnFfiFrameResult.SKIPPED
    }

    val session = renderSession ?: return MlnFfiFrameResult.SKIPPED
    // A false return means MapLibre had nothing to draw, which is ordinary before the style's first
    // update and after an attach until the loop pumps the new size.
    if (!session.renderUpdate()) {
      requestRender()
      return MlnFfiFrameResult.SKIPPED
    }

    if (!hasRenderedAFrame) {
      hasRenderedAFrame = true
      logger?.i {
        "Rendered the first map frame with $backend on ${Thread.currentThread().name}, " +
          "extent ${frame.extent}"
      }
    }
    lastRenderTime = renderStart
    reportFrameRate()
    return MlnFfiFrameResult.RENDERED
  }

  override fun close() {
    if (closed) return
    closed = true
    try {
      stopLoop(endOutstandingMove = true)
    } finally {
      hostSession = null
    }
  }

  /**
   * Closes the render session and then the loop that owns the map. The order is enforced natively:
   * MapLibre refuses to destroy a map that still has a session attached.
   */
  private fun stopLoop(endOutstandingMove: Boolean = false) {
    val abandoned = mutableListOf<PendingMapAction>()
    val stopping = stateLock.withLock {
      val current = loop
      loop = null
      abandoned += pendingMapActions
      pendingMapActions.clear()
      current
    }
    abandoned.forEach { it.abandon() }
    closeRenderSession()
    try {
      stopping?.close()
    } finally {
      // After the join, so the owner thread is gone and this is the only reader of that state.
      if (endOutstandingMove) {
        isGestureInProgress = false
        endCameraMove()
      }
      resumeStrandedTransitions()
    }
  }

  /**
   * Drops the render session, closing it if there is still a thread allowed to. Never throws, so
   * teardown and recovery paths do not have to guard it.
   *
   * The bookkeeping is cleared first and unconditionally: closing can throw after a lost device,
   * and a stale [attachedTarget] would leave the next frame retargeting a dead session or attaching
   * a second one to a map that natively permits only one.
   */
  private fun closeRenderSession() {
    val handle = renderSession
    renderSession = null
    attachedTarget = null
    if (handle == null) return

    val host = hostSession
    if (host == null) {
      // The handle can only be closed by the thread that attached it, reached through the host, so
      // reporting the leak is all that is left.
      logger?.w { "Leaking a MapLibre render session: its host surface is already gone" }
      return
    }
    runCatching { host.withRendererAccess { handle.close() } }
      .onFailure { logger?.e(it) { "Failed to close the MapLibre render session" } }
  }

  // endregion

  // region the map's owner thread

  /**
   * Returns the loop for [extent], starting one or replacing it as needed. Renderer thread only.
   */
  private fun ensureLoop(extent: MlnFfiMapExtent): MlnFfiMapRuntimeLoop {
    val existing = loop
    if (existing != null && existing.scaleFactor == extent.scaleFactor) return existing

    if (existing != null) {
      // pixelRatio is fixed at creation — mbgl holds it const — so a density change cannot be
      // applied by resizing or re-attaching; the map has to be rebuilt.
      logger?.i {
        "Display scale changed from ${existing.scaleFactor} to ${extent.scaleFactor}; " +
          "recreating the map"
      }
      // Where the camera is now, not where it was last asked to be, and read before the loop stops.
      existing.call { it.camera.toCameraPosition() }?.let(::recordCamera)
      styleBinding?.unload()
      stopLoop()
      // The new map starts styleless, so the style must not be skipped as already applied.
      appliedStyle = null
    }

    val started =
      MlnFfiMapRuntimeLoop(
        extent = extent,
        runtimeOptions = runtimeOptions,
        logger = logger,
        onMapCreated = ::onMapCreated,
        onEvent = ::handleEvent,
        onEventsDrained = ::flushTransitionResumes,
        requestFrame = ::requestRender,
      )
    val deferredActions = stateLock.withLock {
      loop = started
      // Under the same lock as the recording, so a setting written while the loop starts is either
      // in this snapshot or posted to the started loop, never lost.
      pendingConfiguration = mapConfiguration.values.toList()
      pendingMapActions.toList().also { pendingMapActions.clear() }
    }
    deferredActions.forEach { action ->
      if (!started.post(action.run, action.abandon)) action.abandon()
    }
    started.start()
    return started
  }

  /** The configuration snapshot handed to a starting loop. Read on its thread, once. */
  @Volatile private var pendingConfiguration: List<(MapHandle) -> Unit> = emptyList()

  /** Runs on the loop's thread, once, before the map is published. */
  private fun onMapCreated(map: MapHandle) {
    val pending = pendingConfiguration
    pendingConfiguration = emptyList()
    pending.forEach { setup ->
      runCatching { setup(map) }.onFailure { logger?.e(it) { "Failed to apply map configuration" } }
    }
    applyRequestedStyle(map)
  }

  /** Attaches or re-attaches the render session, returning whether one is usable. */
  private fun ensureAttached(map: MapHandle, frame: MlnFfiMapFrame): Boolean {
    val extent = frame.extent
    if (extent.isEmpty) return false

    val key = TargetKey(frame.target.generation, extent)
    val attached = attachedTarget
    if (attached == key && renderSession != null) return true

    // Follow the host's new target in place where possible. A renderer compiles its shaders for one
    // pixel ratio, so a scale-factor change needs a new renderer either way.
    val live = renderSession
    if (live != null && attached != null && attached.extent.scaleFactor == extent.scaleFactor) {
      if (retargetBorrowedTexture(live, frame.target, extent)) {
        attachedTarget = key
        retargetCount++
        // The replacement texture holds nothing yet; this request buys the frame that fills it.
        renderRequested.set(true)
        return true
      }
    }

    // Attaching before closing throws, because a map permits only one live session.
    closeRenderSession()

    // There is no map.resize: attaching sets the map's size from the descriptor's logical extent,
    // and the map publishes the result through MapHandle.size at its next pump.
    renderSession =
      try {
        attachBorrowedTexture(map, frame.target, extent)
      } catch (error: Throwable) {
        logger?.e(error) { "Failed to attach a render session to the host target" }
        throw error
      }
    attachedTarget = key
    attachCount++
    // The new texture holds nothing yet; this request buys the frame that fills it.
    renderRequested.set(true)
    return true
  }

  private fun attachBorrowedTexture(
    map: MapHandle,
    target: MlnFfiRenderTarget,
    extent: MlnFfiMapExtent,
  ): RenderSessionHandle =
    when (target) {
      is VulkanImageTarget -> map.attachVulkanBorrowedTexture(target.toDescriptor(extent))
      is MetalTextureTarget -> map.attachMetalBorrowedTexture(target.toDescriptor(extent))
      is OpenGlTextureTarget -> {
        target.makeContextCurrent()
        map.attachOpenGLBorrowedTexture(target.toDescriptor(extent))
      }
    }

  /**
   * Hands [target] to a live session, keeping its renderer, and reports whether it took it.
   *
   * Since maplibre-native-ffi #485 a borrowed-texture session can take a replacement texture
   * instead of being closed and re-attached, keeping the tile pyramid, atlases, symbol placement,
   * and renderer-held feature state. A refusal leaves the session rendering into the texture it
   * already has, so the caller falls back to closing and attaching.
   */
  private fun retargetBorrowedTexture(
    session: RenderSessionHandle,
    target: MlnFfiRenderTarget,
    extent: MlnFfiMapExtent,
  ): Boolean {
    try {
      when (target) {
        is VulkanImageTarget -> session.setVulkanBorrowedTextureTarget(target.toDescriptor(extent))
        is MetalTextureTarget -> session.setMetalBorrowedTextureTarget(target.toDescriptor(extent))
        is OpenGlTextureTarget -> {
          target.makeContextCurrent()
          session.setOpenGLBorrowedTextureTarget(target.toDescriptor(extent))
        }
      }
    } catch (error: InvalidArgumentException) {
      // A replacement belonging to another device.
      return refusedTarget(error)
    } catch (error: UnsupportedFeatureException) {
      // A replacement in another pixel format.
      return refusedTarget(error)
    }
    return true
  }

  /**
   * Narrower than `catch (MaplibreException)` deliberately: those two are the refusals the FFI
   * documents for a replacement target, and the only ones a re-attach can fix. Its siblings —
   * `WrongThreadException`, `InvalidStateException` — are bugs here and must not be swallowed.
   */
  private fun refusedTarget(error: MaplibreException): Boolean {
    logger?.d(error) {
      "The render session would not take the host's replacement target; re-attaching instead"
    }
    return false
  }

  /**
   * The logical half of a borrowed-texture descriptor; the physical size is stated separately, and
   * MapLibre rejects a pair that does not agree.
   */
  private fun MlnFfiMapExtent.toFfiExtent() =
    RenderTargetExtent(
      width = width.coerceAtLeast(1),
      height = height.coerceAtLeast(1),
      scaleFactor = scaleFactor,
    )

  private fun VulkanImageTarget.toDescriptor(extent: MlnFfiMapExtent) =
    VulkanBorrowedTextureDescriptor(
        extent = extent.toFfiExtent(),
        physicalWidth = extent.physicalWidth.coerceAtLeast(1),
        physicalHeight = extent.physicalHeight.coerceAtLeast(1),
        context = context.toFfi(),
        image = NativePointer.ofAddress(image.address),
        imageView = NativePointer.ofAddress(imageView.address),
        format = format,
        initialLayout = initialLayout,
      )
      .also { it.finalLayout = finalLayout }

  private fun MetalTextureTarget.toDescriptor(extent: MlnFfiMapExtent) =
    MetalBorrowedTextureDescriptor(
      extent = extent.toFfiExtent(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1),
      texture = NativePointer.ofAddress(texture.address),
    )

  private fun OpenGlTextureTarget.toDescriptor(extent: MlnFfiMapExtent) =
    OpenGLBorrowedTextureDescriptor(
      extent = extent.toFfiExtent(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1),
      context =
        when (val handles = context) {
          is EglContextHandles -> handles.toFfi()
          is WglContextHandles -> handles.toFfi()
        },
      texture = textureName,
      target = textureTarget,
    )

  // endregion

  // region events, on the map's owner thread

  /** Translates one runtime event. Runs on the map's owner thread, as do the callbacks it makes. */
  private fun handleEvent(event: RuntimeEvent) {
    when (event.type) {
      RuntimeEventType.MAP_RENDER_UPDATE_AVAILABLE -> requestRender()

      RuntimeEventType.MAP_RENDER_FRAME_FINISHED -> {
        val payload = event.payload
        if (payload is RuntimeEventPayload.RenderFrame && payload.needsRepaint) requestRender()
      }

      RuntimeEventType.MAP_STYLE_LOADED -> {
        // A new style replaces every source and layer, so descriptors holding the previous binding
        // must degrade rather than write into a style that no longer exists.
        styleBinding?.unload()
        val binding = SessionStyleBinding().also { styleBinding = it }
        callbacks.onStyleChanged(this, MlnFfiStyle(binding, ::imageScale))
      }

      RuntimeEventType.MAP_LOADING_FINISHED -> callbacks.onMapFinishedLoading(this)

      RuntimeEventType.MAP_LOADING_FAILED -> {
        // The only channel for a URL style's failure; a malformed inline style also throws from the
        // setter. See applyRequestedStyle.
        val reason = event.message.ifBlank { "MapLibre failed to load the map" }
        logger?.e { "Map loading failed (code ${event.code}): $reason" }
        callbacks.onMapFailLoading(reason)
      }

      RuntimeEventType.MAP_CAMERA_WILL_CHANGE -> beginCameraMove()

      RuntimeEventType.MAP_CAMERA_IS_CHANGING -> callbacks.onCameraMoved(this)

      RuntimeEventType.MAP_CAMERA_DID_CHANGE -> {
        callbacks.onCameraMoved(this)
        // A drag is a stream of jumps, each with its own did-change, so ending the move here would
        // report a move that started and finished between two pointer samples.
        if (!isGestureInProgress) endCameraMove()
      }

      RuntimeEventType.MAP_CAMERA_TRANSITION_FINISHED -> {
        val payload = event.payload
        if (payload !is RuntimeEventPayload.CameraTransitionFinished) {
          logger?.w { "A camera transition finished without a payload naming it" }
        } else {
          val id = payload.transitionId
          if (currentTransitionId == id) currentTransitionId = null
          val waiter = transitionWaiters.remove(id)
          if (waiter == null) {
            // Expected after a cancellation: the caller withdrew before native finished.
            logger?.v { "Ignoring the end of unknown camera transition $id" }
          } else {
            // Resumed after the drain: this event is queued immediately before the transition's
            // MAP_CAMERA_DID_CHANGE, so a caller resumed now would read the camera too early.
            pendingResumes += waiter
          }
        }
      }

      RuntimeEventType.MAP_RENDER_ERROR ->
        logger?.e { "MapLibre render error: ${event.message.ifBlank { "unknown" }}" }

      RuntimeEventType.MAP_STYLE_IMAGE_MISSING ->
        // Supplying the image would need a callback the common API does not have; recorded in
        // .agents/docs/COMMON_API_GAPS.md.
        logger?.d { "Style image missing: ${event.message}" }

      // Nothing in MapAdapter.Callbacks corresponds to these, so acting on them would mean
      // inventing API. Named rather than omitted, so the branch below means "an event this build
      // has never seen".
      //
      // MAP_IDLE and MAP_LOADING_STARTED: the callbacks report a style load's outcome, not its
      // phases, and frames are driven by MapLibre asking to be drawn rather than by going idle.
      // The MAP_RENDER_* pair and MAP_TILE_ACTION are per-frame and per-tile telemetry, and the
      // one frame figure the callbacks want comes from MAP_RENDER_FRAME_FINISHED above.
      // The MAP_STILL_IMAGE_* pair answers a still-image request this session never makes.
      RuntimeEventType.MAP_IDLE,
      RuntimeEventType.MAP_LOADING_STARTED,
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

  /**
   * Reports that the camera started moving, unless that has already been reported.
   *
   * A move spans the gesture rather than the jump, matching what the Android and iOS SDKs report:
   * MapLibre's per-change events would otherwise flip `isCameraMoving` on and off within one event
   * drain, which Compose never observes. The reason is re-reported when it changes, since the
   * gesture flag is set from the UI thread and can arrive after a drag's first camera change.
   */
  private fun beginCameraMove() {
    val reason =
      if (isGestureInProgress) CameraMoveReason.GESTURE else CameraMoveReason.PROGRAMMATIC
    if (reportedMoveReason == reason) return
    reportedMoveReason = reason
    callbacks.onCameraMoveStarted(this, reason)
  }

  /** Reports that the camera stopped moving, if it was reported as moving. */
  private fun endCameraMove() {
    if (reportedMoveReason == null) return
    reportedMoveReason = null
    callbacks.onCameraMoveEnded(this)
  }

  /** Reads back what MapLibre stored for a style image. Exists for tests. */
  internal fun styleImageInfo(imageId: String): StyleImageInfo? = runOnMap {
    it.styleImageInfo(imageId)
  }

  /** The live style's layer order, for diagnostics and integration tests. */
  internal fun currentStyleLayerIds(): List<String> = runOnMap { it.styleLayerIds() }.orEmpty()

  /**
   * The scale style images are rasterized at. Taken from the loop, not the map, because it is fixed
   * for that map's lifetime and this is read while handling an event on the owner thread.
   */
  private fun imageScale(): Float = (loop?.scaleFactor ?: 1.0).toFloat()

  /** Marks a frame worth drawing and asks the host for one. Safe from any thread. */
  private fun requestRender() {
    renderRequested.set(true)
    hostSession?.requestFrame()
  }

  /**
   * Whether a frame starting at [now] is far enough from the last one to draw under the cap.
   *
   * The cap filters an arriving cadence rather than driving one, so it needs
   * [FRAME_INTERVAL_SLACK]: a cap at the display's own rate would otherwise reject any interval
   * measured a microsecond short, halving the frame rate. The slack is a fraction so the tolerance
   * scales with the cap.
   */
  private fun allowRenderNow(now: TimeSource.Monotonic.ValueTimeMark): Boolean {
    val fps = maximumFps ?: return true
    if (fps <= 0) return true
    val minimumInterval = 1.0 / fps
    val elapsed = (now - lastRenderTime).toDouble(DurationUnit.SECONDS)
    return elapsed >= minimumInterval * (1.0 - FRAME_INTERVAL_SLACK)
  }

  private fun reportFrameRate() {
    val now = frameTimer.markNow()
    val elapsed = (now - lastFrameTime).toDouble(DurationUnit.SECONDS)
    lastFrameTime = now
    if (elapsed > 0.0) callbacks.onFrame(1.0 / elapsed)
  }

  // endregion

  // region dispatch

  /**
   * Queues [action] for the map's owner thread, dropping it if there is no map. For actions on the
   * map as it is now — a pan, a zoom, a cancel — as opposed to settings; see [configureMap].
   */
  private fun onMap(action: (MapHandle) -> Unit) {
    loop?.post(action)
  }

  /**
   * Queues a one-shot action until a map exists, including before the first render creates its
   * loop.
   */
  private fun postWhenMapExists(action: (MapHandle) -> Unit, abandon: () -> Unit): Boolean {
    val current = stateLock.withLock {
      if (closed) return false
      loop.also { if (it == null) pendingMapActions += PendingMapAction(action, abandon) }
    }
    return current?.post(action, abandon) ?: true
  }

  /**
   * Records [action] as part of the map's configuration under [key], and applies it now if there is
   * a map.
   *
   * Settings must survive being set before any map exists, and a density change replacing the map
   * underneath — which, unlike Android, is invisible to Compose. [key] makes the record
   * last-write-wins rather than a growing log of every value ever set.
   */
  private fun configureMap(key: String, action: (MapHandle) -> Unit) {
    val current = stateLock.withLock {
      mapConfiguration[key] = action
      loop
    }
    current?.post(action)
  }

  private fun recordCamera(position: CameraPosition) {
    requestedCamera = position
    configureMap(CAMERA_KEY) { map -> map.jumpTo(position.toCameraOptions(layoutDirection)) }
  }

  /**
   * Runs [action] on the map's owner thread and waits, or returns [fallback] if there is no map.
   */
  private fun <T> withMap(fallback: T, action: (MapHandle) -> T): T = loop?.call(action) ?: fallback

  /** Runs [action] on the map's owner thread and waits, or returns null if there is no map. */
  private fun <T> runOnMap(action: (MapHandle) -> T): T? = loop?.call(action)

  /** Runs [action] on the host's renderer thread, where the render session lives. */
  private fun <T> withRendererAccess(action: () -> T): T? {
    val host = hostSession ?: return null
    return host.withRendererAccess(action)
  }

  // endregion

  // region MapAdapter

  override fun setBaseStyle(style: BaseStyle) {
    if (style == requestedStyle) return
    styleBinding?.unload()
    requestedStyle = style
    // Reported before the new style is requested, as both mobile adapters do: this disposes the
    // composition holding the old style's sources and layers, which would otherwise recompose
    // against a style node whose base layers are being replaced and fail anchor validation.
    callbacks.onStyleChanged(this, null)
    onMap(::applyRequestedStyle)
  }

  /** Applies whatever style was last asked for, on the map's owner thread. */
  private fun applyRequestedStyle(map: MapHandle) {
    val style = requestedStyle ?: return
    if (style == appliedStyle) return
    // setStyleJson parses inline, so a malformed style throws as well as queueing
    // MAP_LOADING_FAILED; the queued event is what reports it, so the throw is only logged.
    try {
      when (style) {
        is BaseStyle.Uri -> map.setStyleUrl(style.uri)
        is BaseStyle.Json -> map.setStyleJson(style.json)
      }
      appliedStyle = style
    } catch (error: MaplibreException) {
      // appliedStyle is deliberately not set, so setting the same style again retries.
      logger?.e(error) { "Failed to apply style $style" }
    }
  }

  /**
   * The camera a caller asked for before the map existed. `MaplibreMap` applies its first camera as
   * soon as it has an adapter, which is before any extent, so reads in that window answer with what
   * was asked for rather than MapLibre's default.
   */
  @Volatile private var requestedCamera: CameraPosition? = null

  override fun getCameraPosition(): CameraPosition =
    withMap(requestedCamera ?: CameraPosition()) { it.camera.toCameraPosition() }

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    recordCamera(cameraPosition)
  }

  override fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    // Recorded as the fit rather than a resolved camera, so a map replaced because the viewport
    // changed is fitted to the new one.
    configureMap(CAMERA_KEY) { map ->
      map.jumpTo(cameraForBounds(map, boundingBox, bearing, tilt, padding))
    }
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
      // The padding that comes back describes the computed fit, and must be applied verbatim.
      fitOptions =
        CameraFitOptions().also {
          it.padding = padding.toEdgeInsets(layoutDirection)
          it.bearing = bearing
          it.pitch = tilt
        },
    )

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    startTransitionAwaitingRelease(duration) { map, animation ->
      map.flyTo(finalPosition.toCameraOptions(layoutDirection), animation)
    }
  }

  override suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) {
    startTransitionAwaitingRelease(duration) { map, animation ->
      map.flyTo(cameraForBounds(map, boundingBox, bearing, tilt, padding), animation)
    }
  }

  /**
   * Starts a camera transition and suspends until MapLibre reports that it released the camera.
   * Resumes normally however the transition ended, as Android's `CancelableCallback.onCancel` does.
   */
  private suspend fun startTransitionAwaitingRelease(
    duration: Duration,
    start: (MapHandle, AnimationOptions) -> Unit,
  ): Unit = suspendCancellableCoroutine { continuation ->
    val queued =
      postWhenMapExists(
        action = { map -> startTransitionOnMap(map, duration, start, continuation) },
        abandon = { if (continuation.isActive) continuation.resume(Unit) },
      )
    if (!queued && continuation.isActive) continuation.resume(Unit)
  }

  /** Starts a queued transition once its map exists. Owner-thread only. */
  private fun startTransitionOnMap(
    map: MapHandle,
    duration: Duration,
    start: (MapHandle, AnimationOptions) -> Unit,
    continuation: CancellableContinuation<Unit>,
  ) {
    // Cancellation while this waits for the first loop must not start a native transition.
    if (!continuation.isActive) return
    val id = ++lastTransitionId
    transitionWaiters[id] = continuation
    currentTransitionId = id
    try {
      start(
        map,
        AnimationOptions().also {
          it.durationMs = duration.inWholeMilliseconds.toDouble()
          it.transitionId = id
        },
      )
    } catch (error: Throwable) {
      // A rejected command emits no event, so its continuation must be removed and failed here.
      forgetTransition(id)
      if (continuation.isActive) continuation.resumeWithException(error)
      return
    }
    // If cancellation won the race with native start, immediately cancel the new transition.
    continuation.invokeOnCancellation { abandonTransition(id) }
  }

  /** Supplies transition ids. Owner-thread state, like the two maps below. */
  private var lastTransitionId = 0L

  /**
   * The transition this session started most recently, cleared when its end is reported.
   * MAP_CAMERA_TRANSITION_FINISHED says a transition released the camera but not why, so this is
   * how "still driving the camera" is told from "a later command took it over".
   */
  private var currentTransitionId: Long? = null

  private val transitionWaiters = mutableMapOf<Long, CancellableContinuation<Unit>>()

  /** Waiters whose transitions ended during the current event drain. Owner-thread state. */
  private val pendingResumes = mutableListOf<CancellableContinuation<Unit>>()

  /** Resumes them once the whole batch has been applied. */
  private fun flushTransitionResumes() {
    if (pendingResumes.isEmpty()) return
    val resuming = pendingResumes.toList()
    pendingResumes.clear()
    resuming.forEach { waiter -> runCatching { waiter.resume(Unit) } }
  }

  private fun forgetTransition(id: Long) {
    transitionWaiters.remove(id)
    if (currentTransitionId == id) currentTransitionId = null
  }

  /** Drops a cancelled coroutine's registration, stopping the camera if the transition is ours. */
  private fun abandonTransition(id: Long) {
    onMap { map ->
      val wasCurrent = currentTransitionId == id
      forgetTransition(id)
      // Guarded on being current so a late cancellation cannot stop a newer animation.
      if (wasCurrent) map.cancelTransitions()
    }
  }

  /**
   * Resolves everything awaiting a transition on a map that is going away; closing a map discards
   * its queued events, so no finish event will follow. Resumed rather than cancelled, so a caller
   * whose scope is still active does not have that scope cancelled.
   */
  private fun resumeStrandedTransitions() {
    val waiters = transitionWaiters.values.toList()
    transitionWaiters.clear()
    currentTransitionId = null
    waiters.forEach { waiter -> runCatching { waiter.resume(Unit) } }
  }

  override fun setCameraBoundingBox(boundingBox: BoundingBox?) =
    setBounds("bounds.constraint") {
      // Unbounded is not world bounds: world bounds clamp longitude to ±180 and stop the map
      // panning across the antimeridian.
      it.bounds =
        boundingBox?.let { box -> BoundsConstraint.Bounded(box.toLatLngBounds()) }
          ?: BoundsConstraint.Unbounded
    }

  override fun setMaxZoom(maxZoom: Double) = setBounds("bounds.maxZoom") { it.maxZoom = maxZoom }

  override fun setMinZoom(minZoom: Double) = setBounds("bounds.minZoom") { it.minZoom = minZoom }

  override fun setMinPitch(minPitch: Double) =
    setBounds("bounds.minPitch") { it.minPitch = minPitch }

  override fun setMaxPitch(maxPitch: Double) =
    setBounds("bounds.maxPitch") { it.maxPitch = maxPitch }

  /**
   * Applies one field of the map's bound options, keyed so each field replays independently.
   * `BoundOptions` is a field mask, so a replacement map is told only what was actually asked for.
   */
  private fun setBounds(key: String, update: (BoundOptions) -> Unit) {
    configureMap(key) { map -> map.bounds = map.bounds.also(update) }
  }

  override fun getVisibleBoundingBox(): BoundingBox =
    withMap(BoundingBox(Position(0.0, 0.0), Position(0.0, 0.0))) {
      it.latLngBoundsForCamera(it.camera).toBoundingBox()
    }

  override fun getVisibleRegion(): VisibleRegion =
    withMap(
      VisibleRegion(Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0))
    ) { map ->
      // The core exposes no such query, so both mobile SDKs build it from corner projections too.
      // latLngBoundsForCamera is axis-aligned and so wrong for a rotated or pitched camera.
      val size = map.size
      val width = size.width.toDouble()
      val height = size.height.toDouble()
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
    // MapLibre produces no frames of its own here, so throttling our renderUpdate calls is the
    // whole implementation, as it is on Android and iOS.
    maximumFps = value.maximumFps
    configureMap("debugOptions") { map ->
      map.debugOptions = buildSet {
        if (value.isTileBordersEnabled) add(DebugOption.TILE_BORDERS)
        if (value.isTileTimestampsEnabled) add(DebugOption.TIMESTAMPS)
        if (value.isCollisionBoxesEnabled) add(DebugOption.COLLISION)
        if (value.isTileParseStatusEnabled) add(DebugOption.PARSE_STATUS)
      }
    }
  }

  override fun setOrnamentSettings(value: OrnamentOptions) {
    // MapLibre Native has no ornament widgets outside the mobile SDKs, so there is nothing to
    // forward.
  }

  override fun setGestureSettings(value: GestureOptions) {
    // Gestures are implemented in Compose, so these options are read by the host's input
    // handling rather than pushed into the map.
  }

  // Projected against the live map rather than through MapHandle.createProjection, whose handle is
  // a snapshot of the transform at creation and would go stale across a camera move.
  override fun positionFromScreenLocation(offset: DpOffset): Position =
    withMap(Position(0.0, 0.0)) { it.latLngForPixel(offset.toScreenPoint()).toPosition() }

  override fun screenLocationFromPosition(position: Position): DpOffset =
    withMap(DpOffset.Zero) { it.pixelForLatLng(position.toLatLng()).toDpOffset() }

  override fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    query(RenderedQueryGeometry.Point(offset.toScreenPoint()), layerIds, predicate)

  override fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    query(
      RenderedQueryGeometry.Box(
        ScreenBox(
          min = DpOffset(rect.left, rect.top).toScreenPoint(),
          max = DpOffset(rect.right, rect.bottom).toScreenPoint(),
        )
      ),
      layerIds,
      predicate,
    )

  /**
   * Runs a rendered-feature query on the current render session. Rendered feature state belongs to
   * the session, so a query before the first frame or during surface loss returns empty rather than
   * throwing.
   */
  private fun query(
    geometry: RenderedQueryGeometry,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    withRendererAccess {
      val session = renderSession
      if (session == null) {
        logger?.d { "Ignoring a rendered feature query: no render session is attached yet" }
        return@withRendererAccess emptyList()
      }
      // Uncaught: past the null check there is no failure here that is not a bug.
      session.queryRenderedFeatures(geometry, renderedQueryOptions(layerIds, predicate)).map {
        it.toGeoJsonFeature()
      }
    } ?: emptyList()

  /**
   * Meters per logical pixel at [latitude], for the map's current zoom.
   *
   * Transcribed from `mbgl::Projection::getMetersPerPixelAtLatitude`, clamps included; note the
   * 512px tile size rather than the more common 256.
   */
  override fun metersPerDpAtLatitude(latitude: Double): Double {
    val zoom = getCameraPosition().zoom.coerceIn(MIN_PROJECTION_ZOOM, MAX_PROJECTION_ZOOM)
    val clamped = latitude.coerceIn(-MERCATOR_MAX_LATITUDE, MERCATOR_MAX_LATITUDE)
    return cos(clamped * PI / 180.0) * EARTH_CIRCUMFERENCE_METERS / (2.0.pow(zoom) * TILE_SIZE)
  }

  // endregion

  // region input, called from Compose

  fun onGestureStarted() {
    setGestureInProgress(true)
  }

  fun onGestureEnded() {
    setGestureInProgress(false)
  }

  /**
   * Records that a gesture is or is not running, here and on the map. The local flag classifies a
   * camera move as [CameraMoveReason.GESTURE]; the map's own flag is what mbgl consults to treat
   * the commands in between as one gesture. Both stay set until cleared, so every `true` needs a
   * `false`, including on cancellation paths.
   */
  private fun setGestureInProgress(active: Boolean) {
    isGestureInProgress = active
    // Posted rather than blocking: a gesture must never wait on the owner thread to finish a style
    // parse before the pointer can move.
    loop?.post(
      action = { map ->
        map.isGestureInProgress = active
        // The end of the gesture is the end of the move, since its jumps stopped reporting their
        // own ends when it began. Posted so it runs after every jump the gesture queued.
        if (!active) endCameraMove()
      }
    )
  }

  /**
   * Pans by a delta, over [duration]. A zero duration is a jump, which is what a drag wants; a
   * discrete input such as an arrow key eases instead.
   */
  fun moveBy(deltaX: Double, deltaY: Double, duration: Duration = Duration.ZERO) {
    onMap { map ->
      if (duration == Duration.ZERO) map.moveBy(deltaX, deltaY)
      else map.moveByAnimated(deltaX, deltaY, duration.toAnimationOptions())
    }
  }

  /** Zooms by a factor about [anchor], over [duration]. See [moveBy] for why zero exists. */
  fun scaleBy(scale: Double, anchor: DpOffset?, duration: Duration = Duration.ZERO) {
    onMap { map ->
      val point = anchor?.toScreenPoint()
      if (duration == Duration.ZERO) map.scaleBy(scale, point)
      else map.scaleByAnimated(scale, point, duration.toAnimationOptions())
    }
  }

  private fun Duration.toAnimationOptions() =
    AnimationOptions().also { it.durationMs = inWholeMilliseconds.toDouble() }

  /**
   * Rotates and pitches together by a delta in degrees, over [duration]. See [moveBy] for why zero
   * exists.
   *
   * A single camera update rather than the FFI's two-point `rotateBy`, which derives an angle
   * between two pointer positions for a two-finger gesture and would rotate around the wrong centre
   * here.
   */
  fun rotateAndPitchBy(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration = Duration.ZERO,
    anchor: DpOffset? = null,
  ) {
    // Reading the current camera and writing the new one must happen together on the owner thread.
    onMap { map ->
      val camera = map.camera
      val target =
        CameraOptions().also {
          it.bearing = (camera.bearing ?: 0.0) + bearingDelta
          it.pitch =
            ((camera.pitch ?: 0.0) + pitchDelta).coerceIn(MIN_PITCH_DEGREES, MAX_PITCH_DEGREES)
          it.anchor = anchor?.toScreenPoint()
        }
      if (duration == Duration.ZERO) map.jumpTo(target)
      else map.easeTo(target, duration.toAnimationOptions())
    }
  }

  /** Reports a click at [offset], in logical pixels. */
  fun onPrimaryClick(offset: DpOffset) {
    val position = runOnMap { it.latLngForPixel(offset.toScreenPoint()).toPosition() } ?: return
    callbacks.onClick(this, position, offset)
  }

  /**
   * Reports a secondary click at [offset] as a long click: a mouse has no press-and-hold
   * convention, so the secondary button stands in for the mobile SDKs' long press.
   */
  fun onSecondaryClick(offset: DpOffset) {
    val position = runOnMap { it.latLngForPixel(offset.toScreenPoint()).toPosition() } ?: return
    callbacks.onLongClick(this, position, offset)
  }

  fun cancelTransitions() {
    onMap { map ->
      // Cleared first, so a later cancellation cannot stop a newer transition.
      currentTransitionId = null
      map.cancelTransitions()
    }
  }
  // endregion
}

private fun VulkanContextHandles.toFfi() =
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
