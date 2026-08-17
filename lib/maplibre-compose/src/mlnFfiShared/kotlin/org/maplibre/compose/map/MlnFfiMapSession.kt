@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiMapRenderer
import org.maplibre.compose.mlnffi.MlnFfiRecoverableFrameException
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.OpenGlContextHandles
import org.maplibre.compose.mlnffi.OpenGlSurfaceTarget
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.compose.mlnffi.WglContextHandles
import org.maplibre.compose.mlnffi.currentMlnFfiThreadName
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.resource.MlnFfiResourceProvider
import org.maplibre.compose.resource.MlnFfiResourceProviderFactory
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.metersPerDpAtLatitude
import org.maplibre.compose.util.renderedQueryOptions
import org.maplibre.compose.util.toCameraOptions
import org.maplibre.compose.util.toCameraPosition
import org.maplibre.compose.util.toDpOffset
import org.maplibre.compose.util.toEdgeInsets
import org.maplibre.compose.util.toGeoJsonFeatures
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
import org.maplibre.nativeffi.error.NativeErrorException
import org.maplibre.nativeffi.error.UnsupportedFeatureException
import org.maplibre.nativeffi.geo.ScreenBox
import org.maplibre.nativeffi.geo.ScreenPoint
import org.maplibre.nativeffi.map.DebugOption
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.query.RenderedQueryGeometry
import org.maplibre.nativeffi.render.MetalBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.OpenGLBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.OpenGLClientApi
import org.maplibre.nativeffi.render.OpenGLContextOwnership
import org.maplibre.nativeffi.render.OpenGLSurfaceDescriptor
import org.maplibre.nativeffi.render.RenderResult
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent
import org.maplibre.nativeffi.render.VulkanBorrowedTextureDescriptor
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventMask
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.style.StyleImageInfo
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

private const val MIN_PITCH_DEGREES = 0.0

/** MapLibre rejects a pitch beyond this, so the drag is clamped rather than throwing. */
private const val MAX_PITCH_DEGREES = 60.0

/** The events [MlnFfiMapSession.handleEvent] consumes. */
private val HANDLED_MAP_EVENTS: RuntimeEventMask =
  RuntimeEventMask.MAP_RENDER_UPDATE_AVAILABLE +
    RuntimeEventMask.MAP_STYLE_LOADED +
    RuntimeEventMask.MAP_LOADING_FINISHED +
    RuntimeEventMask.MAP_IDLE +
    RuntimeEventMask.MAP_LOADING_FAILED +
    RuntimeEventMask.MAP_CAMERA_WILL_CHANGE +
    RuntimeEventMask.MAP_CAMERA_IS_CHANGING +
    RuntimeEventMask.MAP_CAMERA_DID_CHANGE +
    RuntimeEventMask.MAP_CAMERA_TRANSITION_FINISHED +
    RuntimeEventMask.MAP_RENDER_ERROR +
    RuntimeEventMask.MAP_STYLE_IMAGE_MISSING

/** The fraction of a capped frame interval a frame may arrive early and still be drawn. */
private const val FRAME_INTERVAL_SLACK = 0.1

/**
 * The runtime and the map belong to [MlnFfiMapRuntimeLoop]'s thread; the render session belongs to
 * the host's renderer thread. A camera transition only steps while frames are being drawn: mbgl
 * advances it from `onDidFinishRenderingFrame`.
 */
internal class MlnFfiMapSession(
  @Volatile internal var callbacks: MapAdapter.Callbacks,
  @Volatile internal var logger: Logger?,
  renderBackend: MapRenderBackend,
  scaleFactor: Double = 1.0,
  @Volatile internal var layoutDirection: LayoutDirection,
  private val cacheFile: Path,
  private val resourceProviderFactory: MlnFfiResourceProviderFactory = ::MlnFfiResourceProvider,
) : MapAdapter, MlnFfiMapRenderer, GestureTarget {

  override val backend: MapRenderBackend = renderBackend
  private val initialExtent = MapExtent.fromLogical(1, 1, scaleFactor)

  /** Guards loop startup and actions accepted before it. */
  private val stateLock = MlnFfiLock()

  @Volatile private var loop: MlnFfiMapRuntimeLoop? = null

  /** One-shot map actions accepted before this session starts. Guarded by [stateLock]. */
  private class PendingMapAction(val run: (MapHandle) -> Unit, val abandon: () -> Unit)

  private val pendingMapActions = mutableListOf<PendingMapAction>()

  /** Bounds fits accepted before the first real render target. Guarded by [stateLock]. */
  private val pendingViewportActions = mutableListOf<PendingMapAction>()

  /** Guarded by [stateLock]; once true, the map has dimensions suitable for fitting bounds. */
  private var hasAttachedViewport = false

  /** Renderer-thread state. */
  private var renderSession: RenderSessionHandle? = null

  @Volatile private var hostSession: MlnFfiMapHostSession? = null

  private data class TargetKey(val generation: Long, val extent: MapExtent)

  private var attachedTarget: TargetKey? = null

  /** Renderer-thread state, read by tests. */
  @Volatile
  internal var attachCount: Int = 0
    private set

  @Volatile
  internal var retargetCount: Int = 0
    private set

  private val renderRequested = AtomicBoolean(true)

  private var hasRenderedAFrame = false

  @Volatile private var closed = false
  private var failureReported = false

  @Volatile private var requestedStyle: BaseStyle? = null
  private var appliedStyle: BaseStyle? = null

  /** Gesture attribution is owner-thread state; input threads communicate only through tokens. */
  private var isGestureInProgress = false
  private val nextGestureToken = AtomicLong(0L)
  private var activeGestureToken: GestureToken? = null
  private var pendingGestureEndToken: GestureToken? = null

  private var reportedMoveReason: CameraMoveReason? = null

  @Volatile private var styleBinding: SessionStyleBinding? = null

  private var styleLoadUnreported = false

  /** Once [unload] runs, writes are dropped rather than reaching a map whose style was replaced. */
  private inner class SessionStyleBinding : MlnFfiStyleBinding {
    @Volatile private var loaded = true

    override val isLoaded: Boolean
      get() = loaded && !closed

    override val logger: Logger?
      get() = this@MlnFfiMapSession.logger

    fun unload() {
      loaded = false
    }

    override fun <T> readMap(action: (MapHandle) -> T): T? {
      if (!isLoaded) return null
      return runOnMap(action)
    }

    /**
     * `addSource`, `removeSource` and `removeImage` notify mbgl of nothing, so they render stale.
     */
    override fun <T> mutateMap(abandon: () -> Unit, action: (MapHandle) -> T): T? {
      if (!isLoaded) {
        abandon()
        return null
      }
      return runOnMap(abandon) { map -> action(map).also { map.requestRepaint() } }
    }

    override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? {
      if (!isLoaded) return null
      return withRendererAccess {
        val session = renderSession
        if (session == null) {
          logger?.d { "Ignoring a render session call: no session is attached yet" }
          return@withRendererAccess null
        }
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
    // An idle map publishes no render update, so a surface that returns after loss is never drawn
    // into without this.
    requestRender()
  }

  override fun onSurfaceChanged(extent: MapExtent) {
    // The map is resized as part of attaching the new target; see ensureAttached.
    requestRender()
  }

  override fun onSurfaceLost() {
    // The render session must go before the host session is dropped: that is the only route to the
    // thread allowed to close the handle.
    logger?.i { "Host surface lost; closing the render session and waiting for a new one" }
    closeRenderSession()
    hostSession = null
  }

  override fun render(frame: MlnFfiMapFrame): MlnFfiFrameResult {
    if (closed || frame.extent.isEmpty) return MlnFfiFrameResult.SKIPPED

    val loop = loop ?: return MlnFfiFrameResult.SKIPPED
    loop.failure?.let { error ->
      if (!failureReported) {
        failureReported = true
        // The host stops driving frames after a failure, so nothing else would close the session.
        close()
        throw IllegalStateException("The MapLibre map runtime failed", error)
      }
      return MlnFfiFrameResult.SKIPPED
    }

    val map = loop.map ?: return MlnFfiFrameResult.SKIPPED

    if (!ensureAttached(map, frame)) return MlnFfiFrameResult.SKIPPED
    // Consumed before rendering, so an update published during the render below is not discarded.
    if (!renderRequested.exchange(false)) return MlnFfiFrameResult.SKIPPED
    // The cap measures start-to-start; measuring from the end of the last render rejects every
    // second frame near the display's rate.
    val renderStart = TimeSource.Monotonic.markNow()
    if (!allowRenderNow(renderStart)) {
      // Throttled, not dropped.
      requestRender()
      return MlnFfiFrameResult.SKIPPED
    }

    val session = renderSession ?: return MlnFfiFrameResult.SKIPPED
    val update =
      try {
        session.renderUpdate()
      } catch (error: NativeErrorException) {
        throw MlnFfiRecoverableFrameException("The MapLibre render session failed", error)
      }
    when (update.result) {
      RenderResult.NO_UPDATE,
      RenderResult.SIZE_PENDING -> return MlnFfiFrameResult.SKIPPED
      RenderResult.TARGET_NOT_READY -> {
        requestRender()
        return MlnFfiFrameResult.SKIPPED
      }
      else -> Unit
    }
    if (update.needsRepaint) requestRender()

    if (!hasRenderedAFrame) {
      hasRenderedAFrame = true
      logger?.i {
        "Rendered the first map frame with $backend on ${currentMlnFfiThreadName()}, " +
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

  fun start() {
    val started = stateLock.withLock {
      check(!closed) { "Cannot start a closed map session" }
      loop?.let {
        return
      }
      val created =
        MlnFfiMapRuntimeLoop(
          extent = initialExtent,
          cacheFile = cacheFile,
          getLogger = { logger },
          resourceProviderFactory = resourceProviderFactory,
          onMapCreated = ::onMapCreated,
          onEvent = ::handleEvent,
          onEventsDrained = ::onEventsDrained,
          requestFrame = ::requestRender,
          mapEventMask = HANDLED_MAP_EVENTS,
        )
      pendingMapActions.forEach { action ->
        if (!created.post(action.run, action.abandon)) action.abandon()
      }
      pendingMapActions.clear()
      loop = created
      created
    }
    started.start()
  }

  /** MapLibre refuses to destroy a map that still has a render session attached. */
  private fun stopLoop(endOutstandingMove: Boolean = false) {
    val abandoned = mutableListOf<PendingMapAction>()
    val stopping = stateLock.withLock {
      val current = loop
      loop = null
      abandoned += pendingMapActions
      pendingMapActions.clear()
      abandoned += pendingViewportActions
      pendingViewportActions.clear()
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
        activeGestureToken = null
        pendingGestureEndToken = null
        endCameraMove()
      }
      resumeStrandedTransitions()
    }
  }

  /**
   * Never throws. The bookkeeping is cleared first and unconditionally: a stale [attachedTarget]
   * would leave the next frame attaching a second session to a map that permits only one.
   */
  private fun closeRenderSession() {
    val handle = renderSession
    renderSession = null
    attachedTarget = null
    if (handle == null) return

    val host = hostSession
    if (host == null) {
      // Only the thread that attached the handle may close it, and that is reached through the
      // host.
      logger?.w { "Leaking a MapLibre render session: its host surface is already gone" }
      return
    }
    runCatching { host.withRendererAccess { handle.close() } }
      .onFailure { logger?.e(it) { "Failed to close the MapLibre render session" } }
  }

  // endregion

  // region the map's owner thread

  /** Runs on the loop's thread, once, before the map is published. */
  private fun onMapCreated(map: MapHandle) {
    applyRequestedStyle(map)
    // A camera set before this map existed reaches it as a queued jump, which a loop that stopped
    // before running it has already abandoned.
    requestedCamera?.let { map.jumpTo(it.toCameraOptions(layoutDirection)) }
  }

  private fun ensureAttached(map: MapHandle, frame: MlnFfiMapFrame): Boolean {
    val extent = frame.extent
    if (extent.isEmpty) return false

    val key = TargetKey(frame.target.generation, extent)
    val attached = attachedTarget
    if (attached == key && renderSession != null) return true

    // A renderer compiles its shaders for one pixel ratio, so a scale-factor change needs a new
    // one.
    val live = renderSession
    if (live != null && attached != null && attached.extent.scaleFactor == extent.scaleFactor) {
      if (retargetBorrowedTexture(live, frame.target, extent)) {
        attachedTarget = key
        retargetCount++
        // The replacement texture holds nothing yet; this request buys the frame that fills it.
        renderRequested.store(true)
        return true
      }
    }

    // Attaching before closing throws, because a map permits only one live session.
    closeRenderSession()

    // There is no map.resize: attaching sets the map's size from the descriptor's logical extent.
    renderSession =
      try {
        attachBorrowedTexture(map, frame.target, extent)
      } catch (error: Throwable) {
        logger?.e(error) { "Failed to attach a render session to the host target" }
        throw error
      }
    attachedTarget = key
    attachCount++
    publishAttachedViewport()
    onMap(::snapshotViewport)
    // The new texture holds nothing yet; this request buys the frame that fills it.
    renderRequested.store(true)
    return true
  }

  private fun attachBorrowedTexture(
    map: MapHandle,
    target: MlnFfiRenderTarget,
    extent: MapExtent,
  ): RenderSessionHandle =
    when (target) {
      is VulkanImageTarget -> map.attachVulkanBorrowedTexture(target.toDescriptor(extent))
      is MetalTextureTarget -> map.attachMetalBorrowedTexture(target.toDescriptor(extent))
      is OpenGlTextureTarget -> {
        target.makeContextCurrent()
        map.attachOpenGLBorrowedTexture(target.toDescriptor(extent))
      }
      is OpenGlSurfaceTarget -> map.attachOpenGLSurface(target.toDescriptor(extent))
    }

  /** Whether [session] took the replacement; a refusal leaves it rendering into its old texture. */
  private fun retargetBorrowedTexture(
    session: RenderSessionHandle,
    target: MlnFfiRenderTarget,
    extent: MapExtent,
  ): Boolean {
    try {
      when (target) {
        is VulkanImageTarget -> session.setVulkanBorrowedTextureTarget(target.toDescriptor(extent))
        is MetalTextureTarget -> session.setMetalBorrowedTextureTarget(target.toDescriptor(extent))
        is OpenGlTextureTarget -> {
          target.makeContextCurrent()
          session.setOpenGLBorrowedTextureTarget(target.toDescriptor(extent))
        }
        is OpenGlSurfaceTarget -> session.setOpenGLSurfaceTarget(target.toDescriptor(extent))
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

  private fun refusedTarget(error: MaplibreException): Boolean {
    logger?.d(error) {
      "The render session would not take the host's replacement target; re-attaching instead"
    }
    return false
  }

  /** MapLibre rejects a descriptor whose logical extent and physical size do not agree. */
  private fun MapExtent.toFfiExtent() =
    RenderTargetExtent(
      width = width.coerceAtLeast(1),
      height = height.coerceAtLeast(1),
      scaleFactor = scaleFactor,
    )

  private fun VulkanImageTarget.toDescriptor(extent: MapExtent) =
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

  private fun MetalTextureTarget.toDescriptor(extent: MapExtent) =
    MetalBorrowedTextureDescriptor(
      extent = extent.toFfiExtent(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1),
      texture = NativePointer.ofAddress(texture.address),
    )

  private fun OpenGlTextureTarget.toDescriptor(extent: MapExtent) =
    OpenGLBorrowedTextureDescriptor(
      extent = extent.toFfiExtent(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1),
      context = context.toFfi(),
      texture = textureName,
      target = textureTarget,
    )

  private fun OpenGlSurfaceTarget.toDescriptor(extent: MapExtent) =
    OpenGLSurfaceDescriptor(
      extent = extent.toFfiExtent(),
      context = context.toFfi(),
      surface = NativePointer.ofAddress(surface.address),
    )

  // endregion

  // region events, on the map's owner thread

  /** Runs on the map's owner thread, as do the callbacks it makes. */
  private fun handleEvent(event: RuntimeEvent) {
    when (event.type) {
      RuntimeEventType.MAP_RENDER_UPDATE_AVAILABLE -> requestRender()

      RuntimeEventType.MAP_STYLE_LOADED -> {
        // Descriptors holding the previous binding must not write into a style that is gone.
        styleBinding?.unload()
        val binding = SessionStyleBinding().also { styleBinding = it }
        callbacks.onStyleChanged(this, MlnFfiStyle(binding, ::imageScale))
        styleLoadUnreported = true
      }

      // mbgl only delivers onDidFinishLoadingMap once a frame has seen the new style as not yet
      // loaded, so a style that parses between two frames is never reported. Idle carries the same
      // guarantee and does arrive, so whichever comes first reports the load.
      RuntimeEventType.MAP_LOADING_FINISHED -> {
        if (styleLoadUnreported) {
          styleLoadUnreported = false
          callbacks.onMapFinishedLoading(this)
        }
      }

      RuntimeEventType.MAP_IDLE -> {
        if (styleLoadUnreported) {
          styleLoadUnreported = false
          callbacks.onMapFinishedLoading(this)
        } else {
          callbacks.onSourceChanged(this, null)
        }
      }

      RuntimeEventType.MAP_LOADING_FAILED -> {
        // The only channel for a URL style's failure; a malformed inline style also throws from the
        // setter.
        val reason = event.message.ifBlank { "MapLibre failed to load the map" }
        logger?.e { "Map loading failed (code ${event.code}): $reason" }
        callbacks.onMapFailLoading(reason)
      }

      RuntimeEventType.MAP_CAMERA_WILL_CHANGE -> beginCameraMove()

      RuntimeEventType.MAP_CAMERA_IS_CHANGING -> {
        loop?.map?.let(::snapshotViewport)
        callbacks.onCameraMoved(this)
      }

      RuntimeEventType.MAP_CAMERA_DID_CHANGE -> {
        loop?.map?.let(::snapshotViewport)
        callbacks.onCameraMoved(this)
        // A drag is a stream of jumps, each with its own did-change.
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
            // MAP_CAMERA_DID_CHANGE, so resuming now would read the camera too early.
            pendingResumes += waiter
          }
        }
      }

      RuntimeEventType.MAP_RENDER_ERROR ->
        logger?.e { "MapLibre render error: ${event.message.ifBlank { "unknown" }}" }

      RuntimeEventType.MAP_STYLE_IMAGE_MISSING ->
        // Supplying the image would need a callback the common API does not have.
        logger?.d { "Style image missing: ${event.message}" }

      // Event types are value classes over Int, so an FFI upgrade can add one this build has never
      // seen. Types this session does not select are never queued.
      else -> logger?.v { "Unrecognized MapLibre event type ${event.type}" }
    }
  }

  /**
   * The reason is re-reported when it changes: the gesture flag is set from the UI thread and can
   * arrive after a drag's first camera change.
   */
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

  /** Exists for tests. */
  internal fun styleImageInfo(imageId: String): StyleImageInfo? = runOnMap {
    it.styleImageInfo(imageId)
  }

  /** Exists for tests. */
  internal fun currentStyleLayerIds(): List<String> = runOnMap { it.styleLayerIds() }.orEmpty()

  private fun imageScale(): Float = (loop?.scaleFactor ?: 1.0).toFloat()

  /** Safe from any thread. */
  private fun requestRender() {
    renderRequested.store(true)
    hostSession?.requestFrame()
  }

  /**
   * The cap filters an arriving cadence rather than driving one, hence [FRAME_INTERVAL_SLACK]: a
   * cap at the display's own rate would otherwise halve the frame rate.
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

  /** Dropped if there is no map. */
  private fun onMap(action: (MapHandle) -> Unit) {
    loop?.post(action)
  }

  /** Test seam for intentionally backlogging owner-thread work without touching the native map. */
  internal fun postOwnerTaskForTest(action: () -> Unit): Boolean =
    loop?.post(action = { action() }) ?: false

  /** Test seam that runs [action] after the next native pump and event drain. */
  internal fun postEventDrainBarrierForTest(action: () -> Unit): Boolean =
    loop?.postEventDrainBarrierForTest(action) ?: false

  /** Queues [action] until a map exists, including before the session starts. */
  private fun postWhenMapExists(action: (MapHandle) -> Unit, abandon: () -> Unit): Boolean {
    val current = stateLock.withLock {
      if (closed) return false
      loop.also { if (it == null) pendingMapActions += PendingMapAction(action, abandon) }
    }
    return current?.post(action, abandon) ?: true
  }

  private fun configureMap(action: (MapHandle) -> Unit) {
    postWhenMapExists(action, abandon = {})
  }

  private fun configureMapWithViewport(action: (MapHandle) -> Unit) {
    postWhenViewportExists(action, abandon = {})
  }

  /** Queues [action] until the first render target has supplied the map's real dimensions. */
  private fun postWhenViewportExists(action: (MapHandle) -> Unit, abandon: () -> Unit): Boolean {
    val current = stateLock.withLock {
      if (closed) return false
      if (!hasAttachedViewport) {
        pendingViewportActions += PendingMapAction(action, abandon)
        return true
      }
      loop
    }
    return current?.post(action, abandon) ?: false
  }

  private fun publishAttachedViewport() {
    val (current, pending) =
      stateLock.withLock {
        if (closed || hasAttachedViewport) return
        hasAttachedViewport = true
        loop to pendingViewportActions.toList().also { pendingViewportActions.clear() }
      }
    pending.forEach { action ->
      if (current?.post(action.run, action.abandon) != true) action.abandon()
    }
  }

  private fun recordCamera(position: CameraPosition) {
    requestedCamera = position
    mirroredCamera = position
    configureMap { map ->
      map.jumpTo(position.toCameraOptions(layoutDirection))
      snapshotViewport(map)
    }
  }

  private fun <T> runOnMap(action: (MapHandle) -> T): T? = runOnMap({}, action)

  private fun <T> runOnMap(abandon: () -> Unit, action: (MapHandle) -> T): T? {
    val current = loop
    if (current == null) {
      abandon()
      return null
    }
    return current.call(action, abandon)
  }

  /** The render session lives on the host's renderer thread. */
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
    // Disposes the composition holding the old style's sources and layers, which would otherwise
    // fail anchor validation against the base layers being replaced.
    callbacks.onStyleChanged(this, null)
    onMap(::applyRequestedStyle)
  }

  /** Owner thread only. */
  private fun applyRequestedStyle(map: MapHandle) {
    val style = requestedStyle ?: return
    if (style == appliedStyle) return
    // setStyleJson parses inline, so a malformed style throws as well as queueing
    // MAP_LOADING_FAILED; the queued event is what reports it.
    try {
      when (style) {
        is BaseStyle.Uri -> map.setStyleUrl(style.uri)
        is BaseStyle.Json -> map.setStyleJson(style.json.encodeToByteArray())
      }
      appliedStyle = style
    } catch (error: MaplibreException) {
      // appliedStyle stays unset so rebuilding the map retries.
      logger?.e(error) { "Failed to apply style $style" }
    }
  }

  /** Answers camera reads made before the map has an extent, rather than MapLibre's default. */
  @Volatile private var requestedCamera: CameraPosition? = null

  @Volatile private var mirroredCamera: CameraPosition = CameraPosition()

  @Volatile
  private var mirroredBoundingBox: BoundingBox = BoundingBox(Position(0.0, 0.0), Position(0.0, 0.0))

  @Volatile
  private var mirroredVisibleRegion: VisibleRegion =
    VisibleRegion(Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0))

  @Volatile private var mirroredWidth: Int = 0

  @Volatile private var mirroredHeight: Int = 0

  /** Owner thread only. Copies native camera and viewport so UI getters never hop. */
  private fun snapshotViewport(map: MapHandle) {
    mirroredCamera = map.camera.toCameraPosition()
    val size = map.size
    mirroredWidth = size.width
    mirroredHeight = size.height
    val corners = map.unprojectedCorners()
    mirroredVisibleRegion =
      VisibleRegion(
        farLeft = corners[0],
        farRight = corners[1],
        nearLeft = corners[2],
        nearRight = corners[3],
      )
    val center =
      map
        .latLngsForPixels(listOf(ScreenPoint(size.width / 2.0, size.height / 2.0)))
        .first()
        .toPosition()
    val unwrapped = corners.map { it.unwrapAround(center) }
    // mbgl wraps unprojected longitudes to ±180, so a viewport astride the antimeridian would hull
    // to a box spanning nearly the whole world. Unwrap the corners around the center first; like
    // GL JS, the box may then extend past ±180.
    mirroredBoundingBox =
      BoundingBox(
        southwest =
          Position(
            longitude = unwrapped.minOf { it.longitude },
            latitude = unwrapped.minOf { it.latitude },
          ),
        northeast =
          Position(
            longitude = unwrapped.maxOf { it.longitude },
            latitude = unwrapped.maxOf { it.latitude },
          ),
      )
  }

  override fun getCameraPosition(): CameraPosition = mirroredCamera

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    recordCamera(cameraPosition)
  }

  override fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    configureMapWithViewport { map ->
      map.jumpTo(cameraForBounds(map, boundingBox, bearing, tilt, padding))
      snapshotViewport(map)
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
    startTransitionAwaitingRelease(duration, requiresViewport = true) { map, animation ->
      map.flyTo(cameraForBounds(map, boundingBox, bearing, tilt, padding), animation)
    }
  }

  /** Resumes normally however the transition ended. */
  private suspend fun startTransitionAwaitingRelease(
    duration: Duration,
    requiresViewport: Boolean = false,
    start: (MapHandle, AnimationOptions) -> Unit,
  ): Unit = suspendCancellableCoroutine { continuation ->
    val queued =
      (if (requiresViewport) ::postWhenViewportExists else ::postWhenMapExists)(
        { map -> startTransitionOnMap(map, duration, start, continuation) },
        { if (continuation.isActive) continuation.resume(Unit) },
      )
    if (!queued && continuation.isActive) continuation.resume(Unit)
  }

  /** Owner thread only. */
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
      // A rejected command emits no event, so nothing else would resume the continuation.
      forgetTransition(id)
      if (continuation.isActive) continuation.resumeWithException(error)
      return
    }
    continuation.invokeOnCancellation { abandonTransition(id) }
  }

  /** Owner-thread state, like the two maps below. */
  private var lastTransitionId = 0L

  /**
   * MAP_CAMERA_TRANSITION_FINISHED says a transition released the camera but not why; this tells
   * "still driving the camera" from "a later command took it over".
   */
  private var currentTransitionId: Long? = null

  private val transitionWaiters = mutableMapOf<Long, CancellableContinuation<Unit>>()

  /** Owner-thread state. */
  private val pendingResumes = mutableListOf<CancellableContinuation<Unit>>()

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

  private fun abandonTransition(id: Long) {
    onMap { map ->
      val wasCurrent = currentTransitionId == id
      forgetTransition(id)
      // Guarded on being current so a late cancellation cannot stop a newer animation.
      if (wasCurrent) map.cancelTransitions()
    }
  }

  /** Closing a map discards its queued events, so no finish event will follow. */
  private fun resumeStrandedTransitions() {
    val waiters = transitionWaiters.values.toList()
    transitionWaiters.clear()
    currentTransitionId = null
    waiters.forEach { waiter -> runCatching { waiter.resume(Unit) } }
  }

  override fun setCameraBoundingBox(boundingBox: BoundingBox?) = setBounds {
    // Unbounded is not world bounds: world bounds clamp longitude to ±180 and stop the map
    // panning across the antimeridian.
    it.bounds =
      boundingBox?.let { box -> BoundsConstraint.Bounded(box.toLatLngBounds()) }
        ?: BoundsConstraint.Unbounded
  }

  override fun setMaxZoom(maxZoom: Double) = setBounds { it.maxZoom = maxZoom }

  override fun setMinZoom(minZoom: Double) = setBounds { it.minZoom = minZoom }

  override fun setMinPitch(minPitch: Double) = setBounds { it.minPitch = minPitch }

  override fun setMaxPitch(maxPitch: Double) = setBounds { it.maxPitch = maxPitch }

  /** `BoundOptions` is a field mask, so only the field [update] touches changes. */
  private fun setBounds(update: (BoundOptions) -> Unit) {
    configureMap { map -> map.bounds = map.bounds.also(update) }
  }

  override fun getVisibleBoundingBox(): BoundingBox = mirroredBoundingBox

  override fun getVisibleRegion(): VisibleRegion = mirroredVisibleRegion

  /**
   * The map's corners as positions, ordered top-left, top-right, bottom-left, bottom-right.
   *
   * `latLngBoundsForCamera` hulls only the top-left and bottom-right corners, so it misses parts of
   * the viewport whenever the camera is rotated or pitched. Unproject all four corners instead.
   */
  private fun MapHandle.unprojectedCorners(): List<Position> {
    val width = size.width.toDouble()
    val height = size.height.toDouble()
    return latLngsForPixels(
        listOf(
          ScreenPoint(0.0, 0.0),
          ScreenPoint(width, 0.0),
          ScreenPoint(0.0, height),
          ScreenPoint(width, height),
        )
      )
      .map { it.toPosition() }
  }

  private fun Position.unwrapAround(center: Position): Position {
    val delta = round((center.longitude - longitude) / 360.0) * 360.0
    return if (delta == 0.0) this else Position(longitude = longitude + delta, latitude = latitude)
  }

  /**
   * Approximate unproject from the mirrored visible-region quad. Exact at the four corners; used
   * off the owner thread, where a native hop would stall Compose.
   */
  private fun interpolatedPositionFromScreen(offset: DpOffset): Position {
    val width = mirroredWidth
    val height = mirroredHeight
    if (width <= 0 || height <= 0) return Position(0.0, 0.0)
    val u = offset.x.value.toDouble() / width
    val v = offset.y.value.toDouble() / height
    val center = mirroredCamera.target
    val top = lerpPosition(mirroredVisibleRegion.farLeft, mirroredVisibleRegion.farRight, u, center)
    val bottom =
      lerpPosition(mirroredVisibleRegion.nearLeft, mirroredVisibleRegion.nearRight, u, center)
    return lerpPosition(top, bottom, v, center)
  }

  private fun interpolatedScreenFromPosition(position: Position): DpOffset {
    val width = mirroredWidth
    val height = mirroredHeight
    if (width <= 0 || height <= 0) return DpOffset.Zero
    val center = mirroredCamera.target
    val uv =
      inverseBilinear(
        position.unwrapAround(center),
        mirroredVisibleRegion.farLeft.unwrapAround(center),
        mirroredVisibleRegion.farRight.unwrapAround(center),
        mirroredVisibleRegion.nearRight.unwrapAround(center),
        mirroredVisibleRegion.nearLeft.unwrapAround(center),
      ) ?: return DpOffset.Zero
    return DpOffset((uv.first * width).dp, (uv.second * height).dp)
  }

  private fun lerpPosition(a: Position, b: Position, t: Double, unwrapAround: Position): Position {
    val from = a.unwrapAround(unwrapAround)
    val to = b.unwrapAround(unwrapAround)
    return Position(
      longitude = from.longitude + (to.longitude - from.longitude) * t,
      latitude = from.latitude + (to.latitude - from.latitude) * t,
    )
  }

  /**
   * Inverse bilinear map of [p] in the quad [a]-[b]-[c]-[d] (top-left, top-right, bottom-right,
   * bottom-left). Returns null when the quad is degenerate.
   */
  private fun inverseBilinear(
    p: Position,
    a: Position,
    b: Position,
    c: Position,
    d: Position,
  ): Pair<Double, Double>? {
    val eX = b.longitude - a.longitude
    val eY = b.latitude - a.latitude
    val fX = d.longitude - a.longitude
    val fY = d.latitude - a.latitude
    val gX = a.longitude - b.longitude + c.longitude - d.longitude
    val gY = a.latitude - b.latitude + c.latitude - d.latitude
    val hX = p.longitude - a.longitude
    val hY = p.latitude - a.latitude
    val k2 = gX * fY - gY * fX
    val k1 = eX * fY - eY * fX + hX * gY - hY * gX
    val k0 = hX * eY - hY * eX
    val v: Double
    val u: Double
    if (abs(k2) < 1e-12) {
      if (abs(k1) < 1e-12) return null
      v = -k0 / k1
      val denom = eX + gX * v
      u = if (abs(denom) < 1e-12) (hY - fY * v) / (eY + gY * v) else (hX - fX * v) / denom
    } else {
      val discriminant = k1 * k1 - 4.0 * k2 * k0
      if (discriminant < 0.0) return null
      val root = sqrt(discriminant)
      val inv = 0.5 / k2
      fun uvFor(chosenV: Double): Pair<Double, Double>? {
        val denom = eX + gX * chosenV
        val chosenU =
          if (abs(denom) < 1e-12) {
            val alt = eY + gY * chosenV
            if (abs(alt) < 1e-12) return null
            (hY - fY * chosenV) / alt
          } else {
            (hX - fX * chosenV) / denom
          }
        return chosenU to chosenV
      }
      val first = uvFor((-k1 - root) * inv)
      val second = uvFor((-k1 + root) * inv)
      val pick =
        listOfNotNull(first, second).minByOrNull { (uu, vv) ->
          val du = if (uu < 0.0) -uu else if (uu > 1.0) uu - 1.0 else 0.0
          val dv = if (vv < 0.0) -vv else if (vv > 1.0) vv - 1.0 else 0.0
          du + dv
        }
      if (pick == null) return null
      u = pick.first
      v = pick.second
    }
    return u to v
  }

  override fun setRenderSettings(value: RenderOptions) {
    maximumFps = value.maximumFps
    configureMap { map ->
      map.debugOptions = buildSet {
        if (value.isTileBordersEnabled) add(DebugOption.TILE_BORDERS)
        if (value.isTileTimestampsEnabled) add(DebugOption.TIMESTAMPS)
        if (value.isCollisionBoxesEnabled) add(DebugOption.COLLISION)
        if (value.isTileParseStatusEnabled) add(DebugOption.PARSE_STATUS)
      }
    }
  }

  override fun setGestureSettings(value: GestureOptions) {
    // Gestures are implemented in Compose, so these options are read by the host's input
    // handling rather than pushed into the map.
  }

  // Not MapHandle.createProjection: that handle snapshots the transform and goes stale on a move.
  override fun positionFromScreenLocation(offset: DpOffset): Position {
    val map = loop?.takeIf { it.isOwnerThread() }?.map
    if (map != null) return map.latLngForPixel(offset.toScreenPoint()).toPosition()
    return interpolatedPositionFromScreen(offset)
  }

  override fun screenLocationFromPosition(position: Position): DpOffset {
    val map = loop?.takeIf { it.isOwnerThread() }?.map
    if (map != null) return map.pixelForLatLng(position.toLatLng()).toDpOffset()
    return interpolatedScreenFromPosition(position)
  }

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

  /** Rendered feature state belongs to the render session, so a query without one is empty. */
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
      session
        .queryRenderedFeatures(geometry, renderedQueryOptions(layerIds, predicate))
        .toGeoJsonFeatures()
    } ?: emptyList()

  override fun metersPerDpAtLatitude(latitude: Double): Double =
    metersPerDpAtLatitude(mirroredCamera.zoom, latitude)

  // endregion

  // region input, called from Compose

  /** The begin is queued with the gesture's first camera command. */
  override fun onGestureStarted(): GestureToken = GestureToken(nextGestureToken.incrementAndFetch())

  /** Applied only once the events produced by all preceding camera work have been drained. */
  override fun onGestureEnded(token: GestureToken) {
    loop?.post(action = { if (activeGestureToken == token) pendingGestureEndToken = token })
  }

  /** Owner thread only. */
  private fun activateGesture(map: MapHandle, token: GestureToken) {
    val active = activeGestureToken
    if (active != null && token.value < active.value) return
    if (active == token) return
    activeGestureToken = token
    pendingGestureEndToken = null
    isGestureInProgress = true
    map.isGestureInProgress = true
  }

  /** Runs once the runtime event queue is momentarily empty. Owner thread only. */
  private fun finishPendingGesture(map: MapHandle) {
    val token = pendingGestureEndToken ?: return
    pendingGestureEndToken = null
    if (activeGestureToken != token) return
    activeGestureToken = null
    isGestureInProgress = false
    map.isGestureInProgress = false
    endCameraMove()
  }

  private fun onEventsDrained(map: MapHandle) {
    snapshotViewport(map)
    finishPendingGesture(map)
    flushTransitionResumes()
  }

  private fun onMap(gestureToken: GestureToken?, action: (MapHandle) -> Unit) {
    onMap { map ->
      gestureToken?.let { activateGesture(map, it) }
      action(map)
    }
  }

  /** A zero [duration] is a jump, which is what a drag wants; a key press eases instead. */
  override fun moveBy(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken?,
  ) {
    onMap(gestureToken) { map ->
      if (duration == Duration.ZERO) map.moveBy(deltaX, deltaY)
      else map.moveByAnimated(deltaX, deltaY, duration.toAnimationOptions())
    }
  }

  override suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    startTransitionAwaitingRelease(duration) { map, animation ->
      activateGesture(map, gestureToken)
      map.moveByAnimated(deltaX, deltaY, animation)
    }
  }

  override fun scaleBy(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken?,
  ) {
    onMap(gestureToken) { map ->
      val point = anchor?.toScreenPoint()
      if (duration == Duration.ZERO) map.scaleBy(scale, point)
      else map.scaleByAnimated(scale, point, duration.toAnimationOptions())
    }
  }

  override suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    startTransitionAwaitingRelease(duration) { map, animation ->
      activateGesture(map, gestureToken)
      map.scaleByAnimated(scale, anchor?.toScreenPoint(), animation)
    }
  }

  private fun Duration.toAnimationOptions() =
    AnimationOptions().also { it.durationMs = inWholeMilliseconds.toDouble() }

  /**
   * Not the FFI's two-point `rotateBy`, which derives an angle between two pointer positions and
   * would rotate around the wrong centre here.
   */
  override fun rotateAndPitchBy(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    anchor: DpOffset?,
    gestureToken: GestureToken?,
  ) {
    // The read and the write must happen together on the owner thread.
    onMap(gestureToken) { map ->
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

  override suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    startTransitionAwaitingRelease(duration) { map, animation ->
      activateGesture(map, gestureToken)
      val camera = map.camera
      map.easeTo(
        CameraOptions().also {
          it.bearing = (camera.bearing ?: 0.0) + bearingDelta
          it.pitch =
            ((camera.pitch ?: 0.0) + pitchDelta).coerceIn(MIN_PITCH_DEGREES, MAX_PITCH_DEGREES)
        },
        animation,
      )
    }
  }

  override fun onPrimaryClick(offset: DpOffset) {
    if (closed) return
    onMap { map ->
      callbacks.onClick(this, map.latLngForPixel(offset.toScreenPoint()).toPosition(), offset)
    }
  }

  /** A mouse has no press-and-hold convention, so the secondary button is the long press. */
  override fun onSecondaryClick(offset: DpOffset) {
    if (closed) return
    onMap { map ->
      callbacks.onLongClick(this, map.latLngForPixel(offset.toScreenPoint()).toPosition(), offset)
    }
  }

  override fun cancelTransitions() {
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

private fun OpenGlContextHandles.toFfi() =
  when (this) {
    is EglContextHandles -> toFfi()
    is WglContextHandles -> toFfi()
  }

private fun EglContextHandles.toFfi() =
  org.maplibre.nativeffi.render.EglContextDescriptor(
    display = NativePointer.ofAddress(display.address),
    config = NativePointer.ofAddress(config.address),
    shareContext =
      if (ownership == OpenGLContextOwnership.DEDICATED) NativePointer.NULL
      else NativePointer.ofAddress(shareContext.address),
    getProcAddress = NativePointer.ofAddress(getProcAddress.address),
    clientApi =
      if (ownership == OpenGLContextOwnership.DEDICATED) {
        if (clientApi == OpenGLClientApi.UNSPECIFIED) OpenGLClientApi.GLES else clientApi
      } else {
        clientApi
      },
    ownership = ownership,
  )

private fun WglContextHandles.toFfi() =
  org.maplibre.nativeffi.render.WglContextDescriptor(
    deviceContext = NativePointer.ofAddress(deviceContext.address),
    shareContext =
      if (ownership == OpenGLContextOwnership.DEDICATED) NativePointer.NULL
      else NativePointer.ofAddress(shareContext.address),
    getProcAddress = NativePointer.ofAddress(getProcAddress.address),
    ownership = ownership,
  )
