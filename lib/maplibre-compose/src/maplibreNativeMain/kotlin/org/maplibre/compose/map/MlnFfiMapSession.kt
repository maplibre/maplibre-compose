@file:OptIn(ExperimentalAtomicApi::class)

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
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.round
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiMapPresentationAnchor
import org.maplibre.compose.mlnffi.MlnFfiMapRenderer
import org.maplibre.compose.mlnffi.MlnFfiRecoverableFrameException
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.OpenGlContextHandles
import org.maplibre.compose.mlnffi.OpenGlSurfaceTarget
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.compose.mlnffi.VulkanSurfaceTarget
import org.maplibre.compose.mlnffi.WglContextHandles
import org.maplibre.compose.mlnffi.currentMlnFfiThreadName
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MlnFfiResourceProvider
import org.maplibre.compose.resource.MlnFfiResourceProviderFactory
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleLoadTracker
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.StyleRequestId
import org.maplibre.compose.style.TrackedStyleLoadState
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
import org.maplibre.nativeffi.camera.BoundsConstraint
import org.maplibre.nativeffi.camera.CameraFitOptions
import org.maplibre.nativeffi.camera.CameraOptions
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.nativeffi.error.InvalidArgumentException
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.error.NativeErrorException
import org.maplibre.nativeffi.error.UnsupportedFeatureException
import org.maplibre.nativeffi.geo.ScreenBox
import org.maplibre.nativeffi.geo.ScreenPoint
import org.maplibre.nativeffi.map.DebugOption
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapProjectionHandle
import org.maplibre.nativeffi.map.TileLodMode as FfiTileLodMode
import org.maplibre.nativeffi.map.TileOptions
import org.maplibre.nativeffi.query.RenderedQueryGeometry
import org.maplibre.nativeffi.render.MetalBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.MetalContextDescriptor
import org.maplibre.nativeffi.render.MetalSurfaceDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.OpenGLBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.OpenGLClientApi
import org.maplibre.nativeffi.render.OpenGLContextOwnership
import org.maplibre.nativeffi.render.OpenGLSurfaceDescriptor
import org.maplibre.nativeffi.render.RenderResult
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent
import org.maplibre.nativeffi.render.VulkanBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.VulkanSurfaceDescriptor
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

internal data class NativeEngineCompatibility(
  val renderBackend: MapRenderBackend,
  val scaleFactor: Double,
)

/**
 * The runtime and the map belong to [MlnFfiMapRuntimeLoop]'s thread; the render session belongs to
 * the host's renderer thread. A camera transition only steps while frames are being drawn: mbgl
 * advances it from `onDidFinishRenderingFrame`.
 */
internal class MlnFfiMapSession(
  private val lifecycleAuthority: MapLifecycleAuthority,
  callbacks: MapAdapter.Callbacks,
  @Volatile internal var logger: MapLog?,
  renderBackend: MapRenderBackend,
  scaleFactor: Double = 1.0,
  @Volatile internal var layoutDirection: LayoutDirection,
  private val cacheFile: Path,
  private val resourceProviderFactory: MlnFfiResourceProviderFactory = ::MlnFfiResourceProvider,
  private val resourceConfig: MapResourceConfig = MapResourceConfig(),
) : MapLifecycleSession, MlnFfiMapRenderer, GestureTarget {

  @Volatile internal var callbacks: MapAdapter.Callbacks = callbacks
  @Volatile internal var durableCallbacks: MapAdapter.Callbacks = EmptyMapAdapterCallbacks
  private val lifecycle by lazy { lifecycleAuthority.bind(this) }
  private val lifecycleCallbacks by lazy { MapLifecycleCallbacks(lifecycle) { this.callbacks } }
  @Volatile private var lifecycleEngineIdentity: EngineMapIdentity? = null
  @Volatile private var lifecycleRenderLease: RenderLease? = null
  /** Presentation producer installed and sampled only on the native map's owner thread. */
  private var ownerThreadRenderLease: RenderLease? = null
  @Volatile private var lifecycleStyleRequestIdentity: StyleRequestIdentity? = null
  @Volatile private var styleEventProducer: StyleEventProducer? = null
  @Volatile private var lifecycleStyleIdentity: StyleIdentity? = null

  override val engineRetention: EngineRetention = EngineRetention.RETAIN

  override val retainsEngineBetweenPresentations: Boolean = true

  override val presentationCompatibilityKey: Any =
    NativeEngineCompatibility(renderBackend = renderBackend, scaleFactor = scaleFactor)

  override val backend: MapRenderBackend = renderBackend
  private val initialExtent = MapExtent.fromLogical(1, 1, scaleFactor)

  /** Guards loop startup and actions accepted before it. */
  private val stateLock = MlnFfiLock()

  @Volatile private var loop: MlnFfiMapRuntimeLoop? = null

  @Volatile private var cameraPadding: EdgeInsets = EdgeInsets.ZERO
  @Volatile private var appliedCameraPadding: EdgeInsets = EdgeInsets.ZERO
  @Volatile private var renderedCameraPadding: EdgeInsets = EdgeInsets.ZERO

  /** One-shot map actions accepted before this session starts. Guarded by [stateLock]. */
  private class PendingMapAction(val run: (MapHandle) -> Unit, val abandon: () -> Unit)

  private val pendingMapActions = mutableListOf<PendingMapAction>()

  /** Guarded by [stateLock]; once true, the map has dimensions suitable for fitting bounds. */
  private var hasAttachedViewport = false

  /** Renderer-thread state. */
  private var renderSession: RenderSessionHandle? = null

  /** Renderer-thread state; the FFI creates its renderer during the first successful render. */
  private var renderSessionReady = false

  /** Any thread may publish style or source state that the next rendered update must receive. */
  private val featureStateReplayPending = AtomicBoolean(false)

  @Volatile private var hostSession: MlnFfiMapHostSession? = null

  /**
   * True once this session has loaded a style. It stays true so a later style switch does not put
   * the load placeholder back over a live map.
   */
  internal var hasLoadedFirstStyle by mutableStateOf(false)
    private set

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

  private var failureReported = false

  @Volatile private var requestedStyle: BaseStyle? = null
  @Volatile private var requestedStyleLoad: RequestedStyleLoad? = null
  private var appliedStyle: BaseStyle? = null
  private var styleLoadTracker: StyleLoadTracker? = null
  private var appliedStyleRequest: StyleRequestId? = null

  /** Gesture attribution is owner-thread state; input threads communicate only through tokens. */
  private var isGestureInProgress = false
  private val nextGestureToken = AtomicLong(0L)
  private var activeGestureToken: GestureToken? = null
  private var pendingGestureEndToken: GestureToken? = null

  private var reportedMoveReason: CameraMoveReason? = null

  @Volatile private var styleBinding: MlnFfiStyleBinding? = null
  private val styleReconciler = StyleReconciler()
  @Volatile private var revisionApplied = false

  internal val loadedStyleIdentity
    get() = styleBinding?.identity

  private var styleLoadPending = false
  private var styleLoadUnreported = false

  /**
   * Owner thread only. URL sources whose TileJSON attribution has already been reported, so each is
   * reported exactly once rather than on every idle.
   */
  private val reportedUrlAttribution = mutableSetOf<String>()

  /**
   * Owner thread only. A URL source's TileJSON — and with it the server's attribution — arrives
   * after its add returns, and the C API has no event for the arrival, so the only moment it can be
   * observed is an idle. Add and remove report themselves from the binding; this reports the
   * sources whose attribution newly appeared.
   */
  private fun reportNewlyArrivedAttribution() {
    val map = loop?.map ?: return
    for (id in map.styleSourceIds()) {
      val info = map.styleSourceInfo(id) ?: continue
      if (
        !info.url.isNullOrEmpty() &&
          !info.attribution.isNullOrEmpty() &&
          reportedUrlAttribution.add(id)
      ) {
        withLifecycleStyle { engine, style ->
          lifecycleCallbacks.onSourceChanged(engine, style, this, id)
        }
      }
    }
  }

  private fun createStyleBinding(): MlnFfiStyleBinding =
    MlnFfiStyleBinding(
      loggerProvider = { logger },
      sessionOpen = { lifecycle.acceptsWork },
      accessMap = { action ->
        if (!lifecycle.acceptsWork) false else runOnMap(action).let { true }
      },
      accessRenderSession = { action ->
        if (!lifecycle.acceptsWork || !renderSessionReady) {
          false
        } else {
          withRendererAccess {
            val session = renderSession
            if (session == null) {
              logger?.d { "Ignoring a render session call: no session is attached yet" }
              false
            } else {
              action(session)
              true
            }
          } ?: false
        }
      },
      sourceChanged = { sourceId ->
        featureStateReplayPending.store(true)
        reportedUrlAttribution.remove(sourceId)
        withLifecycleStyle { engine, style ->
          lifecycleCallbacks.onSourceChanged(engine, style, this, sourceId)
        }
      },
      getScale = ::imageScale,
    )

  @Volatile private var maximumFps: Int? = null
  private var cameraConstraints: CameraConstraints? = null
  private var tileLodOptions: TileLodOptions = TileLodOptions.Standard
  private var lastRenderTime = TimeSource.Monotonic.markNow()

  private val frameTimer = TimeSource.Monotonic
  private var lastFrameTime = frameTimer.markNow()

  // region host surface lifecycle

  override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
    if (!lifecycle.acceptsWork) {
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
    if (!lifecycle.acceptsWork || frame.extent.isEmpty) return MlnFfiFrameResult.SKIPPED

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
    renderedCameraPadding = appliedCameraPadding

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
    if (update.result == RenderResult.RENDERED) {
      renderSessionReady = true
      if (featureStateReplayPending.compareAndSet(true, false)) {
        if (styleBinding?.featureStateStore?.replay(session) == true) requestRender()
      }
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

  override fun presentationAnchor(extent: MapExtent): MlnFfiMapPresentationAnchor {
    val padding = renderedCameraPadding
    return MlnFfiMapPresentationAnchor(
      x =
        ((extent.physicalWidth + (padding.left - padding.right) * extent.scaleFactor) / 2.0)
          .toInt(),
      y =
        ((extent.physicalHeight + (padding.top - padding.bottom) * extent.scaleFactor) / 2.0)
          .toInt(),
    )
  }

  override fun close() {
    try {
      endCameraMove()
    } finally {
      lifecycle.close()
    }
  }

  override suspend fun awaitClosed() {
    lifecycle.awaitClosed()
  }

  internal fun preparePresentation() {
    hasLoadedFirstStyle = false
  }

  internal val isPresentationPublished: Boolean
    get() = lifecycleAuthority.acceptsPresentation(this)

  /** Commits a render lease in the apply phase when no physical detachment must finish first. */
  internal fun beginPresentationAttachment(): Boolean =
    lifecycleAuthority.selectAdapterForPresentation(this) && lifecycle.beginAttachIfOpen()

  override suspend fun attachPresentation() {
    lifecycle.attachRetainedEngine()
  }

  internal fun publishRetainedStyle() {
    styleBinding?.let { callbacks.onStyleChanged(this, it) }
  }

  override suspend fun detachPresentation() {
    lifecycle.detachCurrentPresentation()
  }

  override suspend fun createEngine(identity: EngineMapIdentity) {
    lifecycleEngineIdentity = identity
    lifecycleStyleRequestIdentity = lifecycle.claimStyleRequestIdentity(identity)
    startEngine(identity)
  }

  override suspend fun attach(identity: EngineMapIdentity, lease: RenderLease) {
    updateOwnerThreadPresentationAfterDrain { ownerThreadRenderLease = lease }
    lifecycleRenderLease = lease
  }

  override suspend fun detach(identity: EngineMapIdentity, lease: RenderLease) {
    stateLock.withLock { hasAttachedViewport = false }
    callbacks = durableCallbacks
    if (lifecycleRenderLease == lease) lifecycleRenderLease = null
    // This must happen before the first suspension. A host may tear down its renderer thread as
    // soon as close() returns, but the native handle can only be closed through that thread.
    closeRenderSessionForLifecycle()
    updateOwnerThreadPresentation {
      if (ownerThreadRenderLease == lease) ownerThreadRenderLease = null
    }
    awaitEventDrain()
  }

  override suspend fun destroyEngine(identity: EngineMapIdentity) {
    if (lifecycleEngineIdentity == identity) {
      lifecycleEngineIdentity = null
      lifecycleStyleRequestIdentity = null
      styleEventProducer = null
      lifecycleStyleIdentity = null
    }
    closePlatform()
  }

  override suspend fun closeResources() {
    val abandoned = stateLock.withLock {
      pendingMapActions.toList().also { pendingMapActions.clear() }
    }
    abandoned.forEach { it.abandon() }
    resumeStrandedTransitions()
  }

  private fun closePlatform() {
    try {
      stopLoop()
    } finally {
      hostSession = null
      // The owner thread is gone, so this is the last published handle. Destruction is any-thread.
      retireProjection()
    }
  }

  fun start() {
    lifecycle.beginAttachIfOpen()
  }

  private fun startEngine(identity: EngineMapIdentity) {
    val started = stateLock.withLock {
      check(lifecycle.acceptsWork) { "Cannot start a closed map session" }
      loop?.let {
        return
      }
      val created =
        MlnFfiMapRuntimeLoop(
          extent = initialExtent,
          cacheFile = cacheFile,
          getLogger = { logger },
          resourceProviderFactory = resourceProviderFactory,
          resourceConfig = resourceConfig,
          onMapCreated = ::onMapCreated,
          onEvent = { handleEvent(identity, it) },
          onEventsDrained = { onEventsDrained(identity, it) },
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
  private fun stopLoop() {
    val abandoned = mutableListOf<PendingMapAction>()
    val stopping = stateLock.withLock {
      val current = loop
      loop = null
      hasAttachedViewport = false
      abandoned += pendingMapActions
      pendingMapActions.clear()
      current
    }
    abandoned.forEach { it.abandon() }
    if (styleBinding != null) callbacks.onStyleChanged(this, null)
    styleBinding?.invalidate()
    styleBinding = null
    appliedStyle = null
    appliedStyleRequest = null
    styleLoadPending = false
    styleLoadUnreported = false
    styleLoadTracker?.engineBecameUnavailable()
    closeRenderSession()
    try {
      stopping?.close()
    } finally {
      // After the join, so the owner thread is gone and this is the only reader of that state.
      isGestureInProgress = false
      activeGestureToken = null
      pendingGestureEndToken = null
      resumeStrandedTransitions()
    }
  }

  /** Best-effort cleanup for render and surface transitions that cannot report a failure. */
  private fun closeRenderSession() {
    releaseRenderSession()?.let { logger?.e(it) { "Failed to close the MapLibre render session" } }
  }

  /** Lifecycle cleanup reports this resource failure so [MapLifecycleAuthority.awaitClosed] can. */
  private fun closeRenderSessionForLifecycle() {
    releaseRenderSession()?.let { throw it }
  }

  /** Clears bookkeeping before attempting the owner-thread close and returns its failure. */
  private fun releaseRenderSession(): Throwable? {
    val handle = renderSession
    renderSession = null
    renderSessionReady = false
    attachedTarget = null
    if (handle == null) return null

    val host = hostSession
    if (host == null) {
      // Only the thread that attached the handle may close it, and that is reached through the
      // host.
      return IllegalStateException(
        "Cannot close the MapLibre render session because its host surface is already gone"
      )
    }
    return runCatching { host.withRendererAccess { handle.close() } }.exceptionOrNull()
  }

  // endregion

  // region the map's owner thread

  /** Runs on the loop's thread, once, before the map is published. */
  private fun onMapCreated(map: MapHandle) {
    applyRequestedStyle(map)
    // A camera set before this map existed reaches it as a queued jump, which a loop that stopped
    // before running it has already abandoned.
    requestedCamera?.let { map.jumpTo(it.toCameraOptions(cameraPadding)) }
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
        onMap(::snapshotViewportAndNotify)
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
    renderSessionReady = false
    featureStateReplayPending.store(true)
    attachedTarget = key
    attachCount++
    publishAttachedViewport()
    onMap(::snapshotViewportAndNotify)
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
      is VulkanSurfaceTarget -> map.attachVulkanSurface(target.toDescriptor(extent))
      is MetalTextureTarget -> map.attachMetalBorrowedTexture(target.toDescriptor(extent))
      is MetalSurfaceTarget -> map.attachMetalSurface(target.toDescriptor(extent))
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
        is VulkanSurfaceTarget -> session.resize(extent.width, extent.height, extent.scaleFactor)
        is MetalTextureTarget -> session.setMetalBorrowedTextureTarget(target.toDescriptor(extent))
        is MetalSurfaceTarget -> session.setMetalSurfaceTarget(target.toDescriptor(extent))
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

  private fun VulkanSurfaceTarget.toDescriptor(extent: MapExtent) =
    VulkanSurfaceDescriptor(
      extent = extent.toFfiExtent(),
      context = context.toFfi(),
      surface = NativePointer.ofAddress(surface.address),
    )

  private fun MetalTextureTarget.toDescriptor(extent: MapExtent) =
    MetalBorrowedTextureDescriptor(
      extent = extent.toFfiExtent(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1),
      texture = NativePointer.ofAddress(texture.address),
    )

  private fun MetalSurfaceTarget.toDescriptor(extent: MapExtent) =
    MetalSurfaceDescriptor(
      extent = extent.toFfiExtent(),
      context = MetalContextDescriptor(device = NativePointer.ofAddress(device.address)),
      layer = NativePointer.ofAddress(layer.address),
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

  private fun reportLoadedStyle(engine: EngineMapIdentity) {
    if (!styleLoadUnreported || !revisionApplied) return
    val request = appliedStyleRequest ?: return
    val identity = styleBinding?.identity ?: return
    if (styleLoadTracker?.reconciled(request, identity) == true) {
      lifecycleStyleIdentity?.let {
        if (lifecycleCallbacks.onMapFinishedLoading(engine, it, this)) {
          styleLoadUnreported = false
          hasLoadedFirstStyle = true
        }
      }
    }
  }

  /** Runs on the map's owner thread, as do the callbacks it makes. */
  private fun handleEvent(engine: EngineMapIdentity, event: RuntimeEvent) {
    val lease = ownerThreadRenderLease
    when (event.type) {
      RuntimeEventType.MAP_RENDER_UPDATE_AVAILABLE -> requestRender()

      RuntimeEventType.MAP_STYLE_LOADED -> {
        styleLoadPending = false
        val producer = styleEventProducer?.takeIf { it.engine == engine } ?: return
        val binding = createStyleBinding()
        val trackerRequest = appliedStyleRequest ?: return binding.invalidate()
        if (styleLoadTracker?.loaded(trackerRequest, binding.identity) != true) {
          binding.invalidate()
          loop?.map?.let(::applyRequestedStyle)
          return
        }
        val acceptedStyle =
          lifecycleCallbacks.onStyleChanged(engine, producer.request, this, binding) { identity ->
            // Live handles from the previous binding must not write into a style that is gone.
            styleBinding?.invalidate()
            styleBinding = binding
            featureStateReplayPending.store(true)
            lifecycleStyleIdentity = identity
            styleLoadUnreported = true
            reportedUrlAttribution.clear()
          }
        if (acceptedStyle == null) {
          binding.invalidate()
          return
        }
        // A producer frame that started before this callback can still hold the previous style.
        // requestRepaint dirties mbgl so the next renderUpdate draws instead of returning
        // NO_UPDATE; requestRender lets that draw through the session skip gate.
        loop?.map?.requestRepaint()
        requestRender()
      }

      // mbgl only delivers onDidFinishLoadingMap once a frame has seen the new style as not yet
      // loaded, so a style that parses between two frames is never reported. Idle carries the same
      // guarantee and does arrive, so whichever comes first reports the load.
      RuntimeEventType.MAP_LOADING_FINISHED -> {
        reportLoadedStyle(engine)
      }

      RuntimeEventType.MAP_IDLE -> {
        if (styleLoadUnreported) {
          reportLoadedStyle(engine)
        } else {
          reportNewlyArrivedAttribution()
        }
      }

      RuntimeEventType.MAP_LOADING_FAILED -> {
        styleLoadPending = false
        // The only channel for a URL style's failure; a malformed inline style also throws from the
        // setter.
        val reason = event.message.ifBlank { "MapLibre failed to load the map" }
        val request = appliedStyleRequest
        val accepted =
          request != null &&
            styleLoadTracker?.failed(
              request,
              TrackedStyleLoadState.Failed.Stage.BASE_STYLE,
              reason,
            ) == true
        if (accepted) {
          styleEventProducer
            ?.takeIf { it.engine == engine }
            ?.let {
              if (lifecycleCallbacks.onMapFailLoading(engine, it.request, this, reason)) {
                logger?.e { "Map loading failed (code ${event.code}): $reason" }
              }
            }
        } else {
          loop?.map?.let(::applyRequestedStyle)
        }
      }

      RuntimeEventType.MAP_CAMERA_WILL_CHANGE -> beginCameraMove(engine, lease)

      RuntimeEventType.MAP_CAMERA_IS_CHANGING -> {
        lease?.let {
          lifecycleCallbacks.onCameraMoved(engine, it, this) {
            loop?.map?.let(::snapshotViewport)
          }
        }
      }

      RuntimeEventType.MAP_CAMERA_DID_CHANGE -> {
        lease?.let {
          if (
            lifecycleCallbacks.onCameraMoved(engine, it, this) {
              loop?.map?.let(::snapshotViewport)
            }
          ) {
            // A drag is a stream of jumps, each with its own did-change.
            if (!isGestureInProgress) endCameraMove(engine, lease)
          }
        }
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
            logger?.d { "Ignoring the end of unknown camera transition $id" }
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
      else -> logger?.d { "Unrecognized MapLibre event type ${event.type}" }
    }
  }

  /**
   * The reason is re-reported when it changes: the gesture flag is set from the UI thread and can
   * arrive after a drag's first camera change.
   */
  private fun beginCameraMove(
    engine: EngineMapIdentity? = lifecycleEngineIdentity,
    lease: RenderLease? = lifecycleRenderLease,
  ) {
    val reason =
      if (isGestureInProgress) CameraMoveReason.GESTURE else CameraMoveReason.PROGRAMMATIC
    if (reportedMoveReason == reason) return
    if (engine != null && lease != null) {
      if (lifecycleCallbacks.onCameraMoveStarted(engine, lease, this, reason)) {
        reportedMoveReason = reason
      }
    }
  }

  private fun endCameraMove(
    engine: EngineMapIdentity? = lifecycleEngineIdentity,
    lease: RenderLease? = lifecycleRenderLease,
  ) {
    if (reportedMoveReason == null) return
    if (engine != null && lease != null) {
      lifecycleCallbacks.onCameraMoveEnded(engine, lease, this) { reportedMoveReason = null }
    }
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
    if (elapsed > 0.0) {
      withLifecyclePresentation { engine, lease ->
        lifecycleCallbacks.onFrame(engine, lease, 1.0 / elapsed)
      }
    }
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
    loop?.postEventDrainBarrier(action) ?: false

  private suspend fun updateOwnerThreadPresentation(action: () -> Unit) {
    val completion = CompletableDeferred<Result<Unit>>()
    val accepted =
      loop?.post(
        action = { completion.complete(runCatching(action)) },
        abandon = {
          completion.complete(Result.failure(IllegalStateException("Map owner loop stopped")))
        },
      ) ?: false
    if (!accepted) {
      completion.complete(Result.failure(IllegalStateException("Map owner loop is unavailable")))
    }
    completion.await().getOrThrow()
  }

  /**
   * Installs a new producer only after events raised without a presentation have been discarded.
   */
  private suspend fun updateOwnerThreadPresentationAfterDrain(action: () -> Unit) {
    val completion = CompletableDeferred<Result<Unit>>()
    val accepted =
      loop?.postEventDrainBarrier(
        action = { completion.complete(runCatching(action)) },
        abandon = {
          completion.complete(Result.failure(IllegalStateException("Map owner loop stopped")))
        },
      ) ?: false
    if (!accepted) {
      completion.complete(Result.failure(IllegalStateException("Map owner loop is unavailable")))
    }
    completion.await().getOrThrow()
  }

  /** Prevents a later attachment from adopting native events queued by the departed lease. */
  private suspend fun awaitEventDrain() {
    val completion = CompletableDeferred<Result<Unit>>()
    val accepted =
      loop?.postEventDrainBarrier(
        action = { completion.complete(Result.success(Unit)) },
        abandon = {
          completion.complete(Result.failure(IllegalStateException("Map owner loop stopped")))
        },
      ) ?: false
    if (!accepted) {
      completion.complete(Result.failure(IllegalStateException("Map owner loop is unavailable")))
    }
    completion.await().getOrThrow()
  }

  /** Queues [action] until a map exists, including before the session starts. */
  private fun postWhenMapExists(action: (MapHandle) -> Unit, abandon: () -> Unit): Boolean {
    val current = stateLock.withLock {
      if (!lifecycle.acceptsWork) return false
      loop.also { if (it == null) pendingMapActions += PendingMapAction(action, abandon) }
    }
    return current?.post(action, abandon) ?: true
  }

  private fun configureMap(action: (MapHandle) -> Unit) {
    postWhenMapExists(action, abandon = {})
  }

  private fun publishAttachedViewport() {
    stateLock.withLock {
      if (!lifecycle.acceptsWork) return
      hasAttachedViewport = true
    }
  }

  private fun recordCamera(position: CameraPosition) {
    requestedCamera = position
    val padding = cameraPadding
    configureMap { map ->
      map.jumpTo(position.toCameraOptions(padding))
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
    styleBinding?.invalidate()
    revisionApplied = false
    hasLoadedFirstStyle = false
    requestedStyle = style
    val engineAvailable = loop != null
    val tracker = styleLoadTracker
    val trackerRequest =
      if (tracker == null) {
        StyleLoadTracker(style, engineAvailable).also { styleLoadTracker = it }.requestId
      } else {
        tracker.request(style, engineAvailable)
      }
    val capturedTrackerRequest = trackerRequest.takeIf { engineAvailable }
    // Disposes the composition holding the old style's sources and layers, which would otherwise
    // fail anchor validation against the base layers being replaced.
    val lifecycleRequest = lifecycleEngineIdentity?.let {
      lifecycleCallbacks.beginStyleRequest(it, this).also { request ->
        lifecycleStyleRequestIdentity = request
      }
    }
    lifecycleStyleIdentity = null
    val load = RequestedStyleLoad(style, capturedTrackerRequest, lifecycleRequest)
    requestedStyleLoad = load
    onMap { applyRequestedStyle(it, load) }
  }

  override suspend fun reconcileStyleRevision(revision: DesiredStyleRevision): Boolean {
    val binding = styleBinding ?: return false
    val tracker = styleLoadTracker ?: return false
    val wasReady = tracker.state is TrackedStyleLoadState.Ready
    val request = tracker.beginReconciliation()
    revisionApplied = false
    hasLoadedFirstStyle = false
    try {
      styleReconciler.apply(binding, revision)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      tracker.failed(
        request,
        TrackedStyleLoadState.Failed.Stage.RECONCILIATION,
        error.message ?: "Style reconciliation failed",
      )
      throw error
    }
    revisionApplied = true
    if (wasReady && tracker.reconciled(request, binding.identity)) {
      hasLoadedFirstStyle = true
    }
    val engine = lifecycleEngineIdentity
    runOnMap {
      it.requestRepaint()
      if (!hasLoadedFirstStyle && engine != null) reportLoadedStyle(engine)
    }
    requestRender()
    return hasLoadedFirstStyle
  }

  override suspend fun replayStyleRevision(revision: DesiredStyleRevision) {
    val binding = styleBinding ?: return
    revisionApplied = false
    hasLoadedFirstStyle = false
    styleReconciler.apply(binding, revision)
    onMap { it.requestRepaint() }
    requestRender()
  }

  /** Owner thread only. */
  private fun applyRequestedStyle(map: MapHandle) {
    requestedStyleLoad?.let { applyRequestedStyle(map, it) }
  }

  /** Owner thread only. */
  private fun applyRequestedStyle(map: MapHandle, load: RequestedStyleLoad) {
    if (load !== requestedStyleLoad) return
    val style = load.style
    if (styleLoadPending) return
    val engine = lifecycleEngineIdentity ?: return
    val lifecycleRequest = load.lifecycleRequest ?: lifecycleStyleRequestIdentity ?: return
    if (load.lifecycleRequest != null && load.lifecycleRequest != lifecycleStyleRequestIdentity) {
      return
    }
    val tracker = styleLoadTracker ?: return
    val request = tracker.beginLoading()
    if (load.trackerRequest != null && load.trackerRequest != request) return
    if (!tracker.shouldApplyToEngine(appliedStyleRequest)) return
    appliedStyleRequest = request
    styleLoadPending = true
    // Replacing the native style disconnects the preceding style event producer. The runtime loop
    // serializes this command with its event callback, so later events belong to this producer.
    styleEventProducer = StyleEventProducer(engine, lifecycleRequest)
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

  private data class StyleEventProducer(
    val engine: EngineMapIdentity,
    val request: StyleRequestIdentity,
  )

  private class RequestedStyleLoad(
    val style: BaseStyle,
    val trackerRequest: StyleRequestId?,
    val lifecycleRequest: StyleRequestIdentity?,
  )

  /** Applied when a map is created. Getters read [mirroredViewport] after native applies it. */
  @Volatile private var requestedCamera: CameraPosition? = null

  /**
   * Applied camera, extents, and a projection frozen at that camera. One write publishes them
   * together so any-thread getters agree with the last native apply. The FFI map lives on the owner
   * thread, so getters read this snapshot instead of hopping. The default answers reads made before
   * the first snapshot.
   */
  private data class MirroredViewport(
    val camera: CameraPosition = CameraPosition(),
    val size: DpSize = DpSize.Zero,
    val visibleRegion: VisibleRegion =
      VisibleRegion(Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0), Position(0.0, 0.0)),
    val boundingBox: BoundingBox = BoundingBox(Position(0.0, 0.0), Position(0.0, 0.0)),
    val projection: MapProjectionHandle? = null,
  )

  @Volatile private var mirroredViewport = MirroredViewport()

  /**
   * Publication of [mirroredViewport] versus a conversion that is using its projection. The owner
   * thread swaps the snapshot and closes the outgoing handle only after that conversion returns.
   */
  private val projectionLock = MlnFfiLock()

  /**
   * A resize changes the projection without a camera event, so Compose overlays that read
   * [MapState.viewport] would keep the previous screen locations unless this reports the new
   * snapshot.
   */
  private fun snapshotViewportAndNotify(map: MapHandle) {
    snapshotViewport(map)
    // The first attach snapshot can land before the lease is Attached. Seed from the snapshot
    // itself so a dropped camera callback cannot leave MapState.viewport null.
    lifecycleAuthority.seedCurrentPresentationViewport(this)
    withLifecyclePresentation { engine, lease ->
      lifecycleCallbacks.onCameraMoved(engine, lease, this)
    }
  }

  /** Owner thread only. Publishes the applied camera and viewport for any-thread getters. */
  private fun snapshotViewport(map: MapHandle) {
    val size = map.size
    val corners = map.unprojectedCorners()
    val visibleRegion =
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
    publishViewport(
      MirroredViewport(
        camera = map.camera.toCameraPosition(),
        size = DpSize(size.width.dp, size.height.dp),
        visibleRegion = visibleRegion,
        boundingBox =
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
          ),
        // A fresh handle per snapshot: createProjection freezes the transform at creation.
        projection = map.createProjection(),
      )
    )
  }

  private fun retireProjection() {
    val previous = projectionLock.withLock {
      val current = mirroredViewport
      mirroredViewport = current.copy(projection = null)
      current
    }
    runCatching { previous.projection?.close() }
  }

  private fun publishViewport(next: MirroredViewport) {
    val previous = projectionLock.withLock {
      val current = mirroredViewport
      mirroredViewport = next
      current
    }
    runCatching { previous.projection?.close() }
  }

  override fun getCameraPosition(): CameraPosition = mirroredViewport.camera

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    recordCamera(cameraPosition)
  }

  override fun setCameraPadding(padding: PaddingValues) {
    val insets = padding.toEdgeInsets(layoutDirection)
    if (cameraPadding == insets) return
    cameraPadding = insets
    configureMap { map ->
      map.jumpTo(CameraOptions().also { it.padding = insets })
      appliedCameraPadding = insets
      snapshotViewport(map)
    }
  }

  override fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    val fit: (MapHandle) -> Unit = { map ->
      map.jumpTo(cameraForBounds(map, boundingBox, bearing, tilt, padding))
      snapshotViewport(map)
    }
    // MapState waits for the current attachment's viewport before it calls this adapter.
    val hasViewport = stateLock.withLock {
      hasAttachedViewport && lifecycle.acceptsWork && loop != null
    }
    check(hasViewport) { "A bounds fit requires the current presentation viewport" }
    check(runOnMap(fit) != null) { "The map became unavailable during the bounds fit" }
  }

  private fun cameraForBounds(
    map: MapHandle,
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ): CameraOptions {
    val persistent = cameraPadding
    val fit = padding.toEdgeInsets(layoutDirection)
    val total = persistent + fit
    val fitted =
      map.cameraForLatLngBounds(
        bounds = boundingBox.toLatLngBounds(),
        fitOptions =
          CameraFitOptions().also {
            it.padding = total
            it.bearing = bearing
            it.pitch = tilt
          },
      )

    // Native returns the fit padding as persistent camera state. Preserve the fitted transform
    // while replacing it with the map's declarative padding.
    map.createProjection().use { projection ->
      projection.setCamera(fitted)
      val size = map.size
      fitted.center =
        projection.latLngForPixel(
          ScreenPoint(
            x = (size.width + persistent.left - persistent.right) / 2.0,
            y = (size.height + persistent.top - persistent.bottom) / 2.0,
          )
        )
    }
    fitted.padding = persistent
    return fitted
  }

  private operator fun EdgeInsets.plus(other: EdgeInsets): EdgeInsets =
    EdgeInsets(
      top = top + other.top,
      left = left + other.left,
      bottom = bottom + other.bottom,
      right = right + other.right,
    )

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    val padding = cameraPadding
    startTransitionAwaitingRelease(duration) { map, animation ->
      map.flyTo(finalPosition.toCameraOptions(padding), animation)
    }
  }

  override suspend fun animateCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
    duration: Duration,
  ) {
    check(stateLock.withLock { hasAttachedViewport }) {
      "A bounds animation requires the current presentation viewport"
    }
    startTransitionAwaitingRelease(duration) { map, animation ->
      map.flyTo(cameraForBounds(map, boundingBox, bearing, tilt, padding), animation)
    }
  }

  /** Resumes normally however the transition ended. */
  private suspend fun startTransitionAwaitingRelease(
    duration: Duration,
    start: (MapHandle, AnimationOptions) -> Unit,
  ): Unit = suspendCancellableCoroutine { continuation ->
    val queued =
      postWhenMapExists(
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
    flushTransitionResumes()
  }

  override fun setCameraConstraints(value: CameraConstraints) {
    if (value == cameraConstraints) return
    cameraConstraints = value
    configureMap { map ->
      map.bounds =
        map.bounds.copy {
          // Unbounded is not world bounds: world bounds clamp longitude to ±180 and stop the map
          // panning across the antimeridian.
          bounds =
            value.boundingBox?.let { box -> BoundsConstraint.Bounded(box.toLatLngBounds()) }
              ?: BoundsConstraint.Unbounded
          minZoom = value.minZoom
          maxZoom = value.maxZoom
          minPitch = value.minPitch
          maxPitch = value.maxPitch
        }
    }
  }

  override fun getVisibleBoundingBox(): BoundingBox = mirroredViewport.boundingBox

  override fun getVisibleRegion(): VisibleRegion = mirroredViewport.visibleRegion

  override fun getViewport(): Viewport? {
    // The map bootstraps at a 1x1 extent, so the mirror describes a real viewport only once a
    // render target has attached with the composable's dimensions.
    if (!stateLock.withLock { hasAttachedViewport }) return null
    // One read so every property comes from the same publish.
    val mirror = mirroredViewport
    if (mirror.size == DpSize.Zero) return null
    return Viewport(
      size = mirror.size,
      visibleBoundingBox = mirror.boundingBox,
      visibleRegion = mirror.visibleRegion,
      metersPerDpAtTarget =
        metersPerDpAtLatitude(mirror.camera.zoom, mirror.camera.target.latitude),
    )
  }

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

  override fun setTileLodSettings(value: TileLodOptions) {
    if (value == tileLodOptions) return
    tileLodOptions = value
    configureMap { map -> map.tileOptions = value.toFfi() }
  }

  /** Test seam: runs [action] on the owner thread and waits for it. */
  internal fun <T> readMap(action: (MapHandle) -> T): T? = runOnMap(action)

  internal suspend fun <T> withPlatformMap(block: PlatformMapScope.() -> T): T {
    val engine = lifecycle.ensureEngine()
    return suspendCancellableCoroutine { continuation ->
      val invocation = PlatformMapInvocation(continuation)
      continuation.invokeOnCancellation { invocation.cancel() }
      val queued =
        postWhenMapExists(
          action = { map ->
            invocation.execute {
              var result: Result<T>? = null
              val engineAccepted =
                lifecycle.acceptEngineEvent(engine) {
                  val authorityAccepted =
                    lifecycleAuthority.acceptEnginePlatformAccess(this) {
                      result = runCatching { PlatformMapScope(map).block() }
                    }
                  if (!authorityAccepted) {
                    throw IllegalStateException(
                      "The native platform map changed before access could begin"
                    )
                  }
                }
              if (!engineAccepted) {
                throw IllegalStateException(
                  "The native platform map changed before access could begin"
                )
              }
              checkNotNull(result).getOrThrow()
            }
          },
          abandon = {
            invocation.fail(
              IllegalStateException("The native platform map changed before access could begin")
            )
          },
        )
      if (!queued) invocation.fail(MapStateClosedException())
    }
  }

  override fun positionFromScreenLocation(offset: DpOffset): Position? = withSnapshotProjection {
    it.latLngForPixel(offset.toScreenPoint()).toPosition()
  }

  override fun screenLocationFromPosition(position: Position): DpOffset? = withSnapshotProjection {
    it.pixelForLatLng(position.toLatLng()).toDpOffset()
  }

  /**
   * Runs [block] on the snapshot's frozen projection. Holds [projectionLock] for the call so the
   * owner thread retires this handle only after [block] returns.
   */
  private inline fun <T> withSnapshotProjection(block: (MapProjectionHandle) -> T): T? =
    projectionLock.withLock {
      val handle = mirroredViewport.projection ?: return@withLock null
      block(handle)
    }

  override suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    query(RenderedQueryGeometry.Point(offset.toScreenPoint()), layerIds, predicate)

  override suspend fun queryRenderedFeatures(
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
  private suspend fun query(
    geometry: RenderedQueryGeometry,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> = suspendCancellableCoroutine { continuation ->
    if (!lifecycle.acceptsWork) {
      continuation.resume(emptyList())
      return@suspendCancellableCoroutine
    }
    val host = hostSession
    if (host == null) {
      continuation.resume(emptyList())
      return@suspendCancellableCoroutine
    }
    val accepted = host.enqueueRenderer {
      if (!continuation.isActive) return@enqueueRenderer
      val session = renderSession
      if (session == null || !renderSessionReady) {
        continuation.resume(emptyList())
        return@enqueueRenderer
      }
      continuation.resumeWith(
        runCatching {
          session
            .queryRenderedFeatures(geometry, renderedQueryOptions(layerIds, predicate))
            .toGeoJsonFeatures()
            // Native walks style layers from the bottom. MapState and GL JS put the
            // feature in front first.
            .asReversed()
        }
      )
    }
    if (!accepted && continuation.isActive) continuation.resume(emptyList())
  }

  override fun metersPerDpAtLatitude(latitude: Double): Double =
    metersPerDpAtLatitude(mirroredViewport.camera.zoom, latitude)

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

  private fun onEventsDrained(engine: EngineMapIdentity, map: MapHandle) {
    val lease = ownerThreadRenderLease ?: return
    lifecycleCallbacks.onPresentationEvent(engine, lease) {
      snapshotViewport(map)
      finishPendingGesture(map)
      flushTransitionResumes()
    }
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
    if (!lifecycle.acceptsWork) return
    val position = withSnapshotProjection { it.latLngForPixel(offset.toScreenPoint()).toPosition() }
    if (position == null) {
      logger?.w { "Dropped a map click at $offset: the map has no viewport" }
      return
    }
    withLifecyclePresentation { engine, lease ->
      lifecycleCallbacks.onClick(engine, lease, this, position, offset)
    }
  }

  /** A mouse has no press-and-hold convention, so the secondary button is the long press. */
  override fun onSecondaryClick(offset: DpOffset) {
    if (!lifecycle.acceptsWork) return
    val position = withSnapshotProjection { it.latLngForPixel(offset.toScreenPoint()).toPosition() }
    if (position == null) {
      logger?.w { "Dropped a map long click at $offset: the map has no viewport" }
      return
    }
    withLifecyclePresentation { engine, lease ->
      lifecycleCallbacks.onLongClick(engine, lease, this, position, offset)
    }
  }

  private inline fun withLifecycleStyle(action: (EngineMapIdentity, StyleIdentity) -> Unit) {
    val engine = lifecycleEngineIdentity ?: return
    val style = lifecycleStyleIdentity ?: return
    action(engine, style)
  }

  private inline fun withLifecyclePresentation(action: (EngineMapIdentity, RenderLease) -> Unit) {
    val engine = lifecycleEngineIdentity ?: return
    val lease = lifecycleRenderLease ?: return
    action(engine, lease)
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
      if (ownership == OpenGLContextOwnership.DEDICATED) NativePointer.NULL_POINTER
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
      if (ownership == OpenGLContextOwnership.DEDICATED) NativePointer.NULL_POINTER
      else NativePointer.ofAddress(shareContext.address),
    getProcAddress = NativePointer.ofAddress(getProcAddress.address),
    ownership = ownership,
  )

private fun TileLodOptions.toFfi(): TileOptions =
  TileOptions().also {
    it.lodMode = mode.toFfi()
    it.lodMinRadius = minRadius
    it.lodScale = scale
    it.lodPitchThreshold = pitchThreshold * PI / 180.0
    it.lodZoomShift = zoomShift
  }

private fun TileLodMode.toFfi(): FfiTileLodMode =
  when (this) {
    TileLodMode.Default -> FfiTileLodMode.DEFAULT
    TileLodMode.Distance -> FfiTileLodMode.DISTANCE
  }
