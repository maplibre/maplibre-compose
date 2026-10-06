package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraAnchor
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.camera.internal.BoxZoomFit
import org.maplibre.compose.camera.internal.CameraCommandGuard
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.camera.internal.boxZoomFit
import org.maplibre.compose.camera.internal.runCameraCommand
import org.maplibre.compose.camera.resolveScreenPoint
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.interaction.BearingSnapping
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameProjection
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiMapRenderer
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiRenderSessions
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleIdentity
import org.maplibre.compose.style.StyleLoadTracker
import org.maplibre.compose.style.StylePresentation
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.StyleRequestId
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.mercatorPixelDistance
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
import org.maplibre.nativeffi.camera.UnitBezier
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.geo.ScreenBox
import org.maplibre.nativeffi.geo.ScreenPoint
import org.maplibre.nativeffi.map.DebugOption
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.ProjectionModeOptions
import org.maplibre.nativeffi.map.TileOptions
import org.maplibre.nativeffi.query.RenderedQueryGeometry
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventMask
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

private const val MinPitchDegrees = 0.0

/** MapLibre rejects a pitch beyond this, so the drag is clamped rather than throwing. */
private const val MaxPitchDegrees = 60.0

/** `util::MAX_ZOOM`, the zoom MapLibre Native clamps to when the map has no maximum. */
private const val MaxNativeZoom = 25.5

/** The zoom curve of a flight, `rho` in `Transform::flyTo`. */
private const val FlightCurve = 1.42

/** The events [MlnFfiMapSession.handleEvent] consumes. */
private val HandledMapEvents: RuntimeEventMask =
  RuntimeEventMask.MAP_RENDER_UPDATE_AVAILABLE +
    RuntimeEventMask.MAP_STYLE_LOADED +
    RuntimeEventMask.MAP_IDLE +
    RuntimeEventMask.MAP_LOADING_FAILED +
    RuntimeEventMask.MAP_CAMERA_WILL_CHANGE +
    RuntimeEventMask.MAP_CAMERA_IS_CHANGING +
    RuntimeEventMask.MAP_CAMERA_DID_CHANGE +
    RuntimeEventMask.MAP_CAMERA_TRANSITION_FINISHED +
    RuntimeEventMask.MAP_RENDER_ERROR +
    RuntimeEventMask.MAP_RENDER_FRAME_FINISHED +
    RuntimeEventMask.MAP_STYLE_IMAGE_MISSING

internal data class NativeEngineCompatibility(
  val renderBackend: MapRenderBackend,
  val scaleFactor: Double,
)

/**
 * The map uses the shared runtime owner through [MlnFfiMapRuntimeLoop]; its render session belongs
 * to the host's renderer thread. [NativePresentation] owns that attachment and [NativeViewport]
 * owns extent acknowledgement and projection snapshots. A camera transition only steps while frames
 * are being drawn: mbgl advances it from `onDidFinishRenderingFrame`.
 */
internal class MlnFfiMapSession(
  private val lifecycleAuthority: MapLifecycleAuthority,
  callbacks: MapAdapter.Callbacks,
  @Volatile internal var logger: MapLog?,
  renderBackend: MapRenderBackend,
  scaleFactor: Double = 1.0,
  @Volatile internal var layoutDirection: LayoutDirection,
  private val owner: MlnFfiRuntime,
) :
  MapAdapter,
  RetainedEngineSteps,
  SessionStamps,
  MlnFfiMapRenderer,
  MlnFfiRenderSessions,
  CameraInputTarget {

  @Volatile internal var callbacks: MapAdapter.Callbacks = callbacks
  @Volatile internal var durableCallbacks: MapAdapter.Callbacks = EmptyMapAdapterCallbacks
  internal val lifecycle =
    lifecycleAuthority.createRetainedEngineLifecycle(steps = this, session = this)
  private val events =
    MapSessionEvents(map = this, stamps = this, postToMain = lifecycleAuthority::postToMain) {
      this.callbacks
    }
  @Volatile private var lifecycleEngineIdentity: EngineMapIdentity? = null
  @Volatile private var lifecycleRenderLease: RenderLease? = null
  /** Presentation producer installed and sampled only on the native map's owner thread. */
  private var ownerThreadRenderLease: RenderLease? = null

  override val retainsEngineBetweenPresentations: Boolean = true

  override val presentationCompatibilityKey: Any =
    NativeEngineCompatibility(renderBackend = renderBackend, scaleFactor = scaleFactor)

  override val backend: MapRenderBackend = renderBackend
  private val initialExtent = MapExtent.fromLogical(1, 1, scaleFactor)

  /**
   * The engine the loop's map belongs to. Written before the loop starts and read on its owner
   * thread.
   */
  private var loopEngine: EngineMapIdentity? = null

  private val viewport = NativeViewport { isClosing }
  internal val presentation: NativePresentation =
    NativePresentation(
      backend = backend,
      isClosing = { lifecycleAuthority.isClosed || isClosing },
      canRender = { !isClosing && lifecycleRenderLease != null },
      getLogger = { logger },
      viewport = viewport,
      requestViewport = { extent ->
        if (viewport.request(extent)) loop.submit(action = ::applyPendingViewport)
      },
    )

  /**
   * This session's executor on the shared owner. Work submitted before the engine exists waits in
   * its queue, and runs in order once [createEngine] starts it. Internal for tests.
   */
  internal val loop =
    MlnFfiMapRuntimeLoop(
      extent = initialExtent,
      owner = owner,
      getLogger = { logger },
      onMapCreated = ::onMapCreated,
      onEvent = { map, event -> handleEvent(checkNotNull(loopEngine), map, event) },
      onEventsDrained = ::onEventsDrained,
      requestFrame = presentation::requestRender,
      mapEventMask = HandledMapEvents,
    )

  private val cameraTransitions = MlnFfiCameraTransitions { logger }

  internal val canPresentFrames: Boolean
    get() = styleLoadTracker.presentation != StylePresentation.Hidden

  private var failureReported = false

  @Volatile private var requestedStyle: BaseStyle? = null
  @Volatile private var requestedStyleLoad: RequestedStyleLoad? = null
  private val styleLoadTracker = StyleLoadTracker()
  private var appliedStyleRequest: StyleRequestId? = null

  /** Gesture attribution is owner-thread state; input threads communicate only through tokens. */
  private val gestureFences = mutableListOf<CameraInputToken>()
  private var activeGestureToken: CameraInputToken? = null
  private var pendingGestureEndToken: CameraInputToken? = null

  @Volatile private var styleBinding: MlnFfiStyleBinding? = null

  /**
   * Exists for tests. Runs on the owner thread after a style load is claimed and before its binding
   * is installed.
   */
  @Volatile internal var beforeStyleInstallForTest: ((MlnFfiStyleBinding) -> Unit)? = null
  private val styleReconciler = StyleReconciler()

  internal val loadedStyleIdentity
    get() = styleBinding?.identity

  /**
   * Owner thread only. URL sources whose TileJSON attribution has already been reported, so each is
   * reported exactly once rather than on every idle.
   */
  private val reportedUrlAttribution = mutableSetOf<String>()

  /**
   * Owner thread only. Checks for attribution received through a URL source's TileJSON after
   * addSource returns. The C API has no TileJSON arrival event, so check on idle.
   */
  private fun reportNewlyArrivedAttribution() {
    val map = loop.map ?: return
    var changed = false
    for (id in map.styleSourceIds()) {
      val info = map.styleSourceInfo(id) ?: continue
      if (
        !info.url.isNullOrEmpty() &&
          !info.attribution.isNullOrEmpty() &&
          reportedUrlAttribution.add(id)
      ) {
        changed = true
      }
    }
    if (changed) styleBinding?.identity?.let { events.styleSourcesChanged(it) }
  }

  private fun createStyleBinding(map: MapHandle): MlnFfiStyleBinding =
    MlnFfiStyleBinding(
      map = map,
      loop = loop,
      loggerProvider = { logger },
      sessionOpen = { !isClosing },
      renderSessions = this,
      sourceChanged = { sourceId ->
        reportedUrlAttribution.remove(sourceId)
      },
      sourceDataFailed = { bindingIdentity, sourceId, error ->
        if (styleBinding?.identity === bindingIdentity) {
          events.styleEvent(bindingIdentity, MapEvent.SourceDataFailed(sourceId, error))
        }
      },
      getScale = ::imageScale,
    )

  @Volatile
  override var maximumFps: Int? = null
    private set

  private var cameraConstraints: CameraConstraints? = null
  private var cameraProjection: CameraProjection = CameraProjection.Perspective
  private var tileLodOptions: TileLodOptions = TileLodOptions.Standard

  // region host surface lifecycle

  override fun onSurfaceAvailable(session: MlnFfiMapHostSession) =
    presentation.onSurfaceAvailable(session)

  override fun onSurfaceChanged(extent: MapExtent) = presentation.requestRender()

  override fun onSurfaceLost(session: MlnFfiMapHostSession) {
    presentation.onSurfaceLost(session)
  }

  override fun render(
    host: MlnFfiMapHostSession,
    frame: MlnFfiMapFrame,
    captureProjection: Boolean,
  ): MlnFfiFrameResult {
    if (isClosing) return MlnFfiFrameResult.AwaitUpdate
    loop.failure?.let { error ->
      if (!failureReported) {
        failureReported = true
        close()
        throw IllegalStateException("The MapLibre map runtime failed", error)
      }
      return MlnFfiFrameResult.AwaitUpdate
    }
    val map = loop.map ?: return MlnFfiFrameResult.AwaitUpdate
    if (styleLoadTracker.presentation == StylePresentation.Retained)
      return MlnFfiFrameResult.AwaitUpdate
    return presentation.render(host, map, frame, captureProjection)
  }

  override fun presentFrame(
    projection: MlnFfiMapFrameProjection?,
    destination: MlnFfiMapDestination,
    scaleFactor: Double,
  ) = viewport.presentFrame(projection, destination, scaleFactor)

  override fun presentationAnchor(extent: MapExtent) = viewport.presentationAnchor(extent)

  override val isClosing: Boolean
    get() = lifecycle.isClosing

  override fun close() {
    lifecycle.close()
  }

  override suspend fun awaitClosed() {
    lifecycle.awaitClosed()
  }

  override fun isCurrentEngine(engine: EngineMapIdentity): Boolean =
    !isClosing && engine == lifecycleEngineIdentity

  override fun isCurrentPresentation(engine: EngineMapIdentity, lease: RenderLease): Boolean =
    !isClosing && engine == lifecycleEngineIdentity && lease == lifecycle.lease

  override fun isCurrentStyleRequest(request: StyleRequestId): Boolean =
    !isClosing && request === styleLoadTracker.requestId

  override fun isCurrentStyle(style: StyleIdentity): Boolean =
    !isClosing && styleLoadTracker.isCurrent(style)

  internal fun preparePresentation() {
    styleLoadTracker.resetPresentation()
  }

  internal val isPresentationPublished: Boolean
    get() = lifecycleAuthority.acceptsPresentation(this)

  /**
   * Requests a render lease in the apply phase. The attachment starts at once, or after a
   * detachment still in progress.
   */
  internal fun beginPresentationAttachment(): Boolean =
    lifecycleAuthority.selectAdapterForPresentation(this) && lifecycle.beginAttach()

  /** Attaches the engine to the current presentation host, creating the engine if needed. */
  suspend fun attachPresentation() {
    lifecycleAuthority.adopt(this)
    lifecycle.attach()
  }

  internal fun publishRetainedStyle() {
    // The installed binding stays after a replacement is requested, and after that replacement
    // fails. Only the current request's style is republished.
    styleBinding?.let(events::styleLoaded)
  }

  /**
   * Ends the current presentation. From here on the engine reports to the map state's durable
   * callbacks; the next presentation installs its own after it attaches.
   */
  override suspend fun detachPresentation() {
    callbacks = durableCallbacks
    lifecycle.detach()
  }

  override suspend fun createEngine(identity: EngineMapIdentity) {
    owner.awaitReady()
    check(!isClosing) { "Cannot start a closed map session" }
    lifecycleEngineIdentity = identity
    loopEngine = identity
    loop.start()
  }

  override suspend fun attach(identity: EngineMapIdentity, lease: RenderLease) {
    // Installs the producer only after events raised without a presentation have been discarded.
    loop.awaitEventsDrained { ownerThreadRenderLease = lease }
    lifecycleRenderLease = lease
    presentation.resume()
  }

  override suspend fun detach(identity: EngineMapIdentity, lease: RenderLease) {
    loop.submit { it.cancelTransitions() }
    viewport.clearRequest(clearApplied = true)
    if (lifecycleRenderLease == lease) lifecycleRenderLease = null
    presentation.release()
    // An unstarted or failed owner cannot process more events or a round-trip. Renderer release
    // above still has to finish before the shared owner's finalizer destroys this map.
    if (!loop.isStarted || loop.failure != null) return
    // Not cancellable: a departed lease must not keep attributing events to itself.
    checkNotNull(
      loop.await(cancellable = false) {
        if (ownerThreadRenderLease == lease) ownerThreadRenderLease = null
      }
    ) {
      "The map owner loop stopped"
    }
    // Prevents a later attachment from adopting native events queued by the departed lease.
    loop.awaitEventsDrained()
  }

  override suspend fun destroyEngine(identity: EngineMapIdentity) {
    if (lifecycleEngineIdentity == identity) lifecycleEngineIdentity = null
    stopLoop()
  }

  override suspend fun closeResources() {
    // Completion can retire independently of a map kept alive by failed renderer release.
    cameraTransitions.releaseAll()
    // Other owner state retires only after stopLoop acknowledges shutdown.
    if (loop.isStarted) return
    loop.close()
    loop.awaitClosed()
    retireOwnerState()
  }

  /** Test seam: adopts this session and attaches it without a presentation reservation. */
  fun start() {
    if (lifecycleAuthority.adopt(this)) lifecycle.beginAttach()
  }

  /** MapLibre refuses to destroy a map that still has a render session attached. */
  private suspend fun stopLoop() {
    viewport.clearRequest(clearApplied = true)
    if (styleBinding != null) {
      lifecycleAuthority.postToMain { callbacks.onStyleChanged(this, null) }
    }
    styleBinding?.invalidate()
    styleBinding = null
    appliedStyleRequest = null
    styleLoadTracker.engineBecameUnavailable()
    // A frame queued behind this release sees the session closing in ensureAttached.
    // A failed renderer release must retain the map and borrowed target, not destroy their owners.
    presentation.release(keepHost = false)
    try {
      if (isClosing || loop.isStarted) {
        loop.close()
        withContext(NonCancellable) { loop.awaitClosed() }
      } else {
        // Initialization did not reach lazy map creation. Release waiting commands without
        // consuming the executor, so a cancelled attachment can be replaced.
        loop.abandonQueuedTasks()
      }
    } finally {
      retireOwnerState()
    }
  }

  /** Only after map shutdown acknowledges that the shared owner will no longer visit this state. */
  private fun retireOwnerState() {
    viewport.retire()
    activeGestureToken?.complete()
    activeGestureToken = null
    pendingGestureEndToken = null
    ownerThreadRenderLease = null
    gestureFences.toList().also { gestureFences.clear() }.forEach { it.complete() }
    if (isClosing || loop.isStarted) cameraTransitions.releaseAll()
  }

  // endregion

  // region the map's owner thread

  /** Runs on the loop's thread, once, before the map is published. */
  private fun onMapCreated(map: MapHandle) {
    applyRequestedStyle(map)
    // A camera set before this map existed also reaches it as a queued jump, but a failed engine
    // creation abandons the jumps queued before it.
    viewport.prepareCamera(map, requestedCamera)
  }

  // endregion

  // region events, on the map's owner thread

  /** Runs on the map's owner thread, as do the callbacks it makes. */
  private fun handleEvent(engine: EngineMapIdentity, map: MapHandle, event: RuntimeEvent) {
    val lease = ownerThreadRenderLease
    val mapEvent = event.toMapEvent()
    when (event.type) {
      RuntimeEventType.MAP_RENDER_UPDATE_AVAILABLE -> presentation.requestRender()

      RuntimeEventType.MAP_STYLE_LOADED -> {
        val binding = createStyleBinding(map)
        val request = appliedStyleRequest ?: return binding.invalidate()
        // The tracker claims the load for its request under its lock, so a base style requested
        // on main before the claim rejects it.
        if (!styleLoadTracker.loaded(request, binding.identity) || isClosing) {
          binding.invalidate()
          return
        }
        beforeStyleInstallForTest?.invoke(binding)
        // Live handles from the previous binding must not write into a style that is gone.
        styleBinding?.invalidate()
        styleBinding = binding
        // setBaseStyle invalidates the installed binding after it requests. A request made after
        // the claim therefore either invalidates this binding or is seen here.
        if (!styleLoadTracker.isCurrent(binding.identity)) {
          binding.invalidate()
          return
        }
        reportedUrlAttribution.clear()
        events.styleLoaded(binding)
        mapEvent?.let { events.styleEvent(binding.identity, it) }
      }

      RuntimeEventType.MAP_IDLE -> {
        if (styleLoadTracker.isReady) reportNewlyArrivedAttribution()
        mapEvent?.let { events.engineEvent(engine, it) }
      }

      RuntimeEventType.MAP_LOADING_FAILED -> {
        // Asynchronous document failures arrive here. Setter exceptions are reported at submission.
        val reason = event.styleLoadFailureReason()
        val request = appliedStyleRequest
        if (request != null && styleLoadTracker.failed(request) && !isClosing) {
          events.styleFailed(request, reason)
          logger?.e { "Map loading failed (code ${event.code}): $reason" }
          mapEvent?.let { events.styleRequestEvent(request, it) }
        }
      }

      // MapState reads the camera and viewport on each of the three, so the mirror the getters
      // read is refreshed before the event is delivered.
      RuntimeEventType.MAP_CAMERA_WILL_CHANGE,
      RuntimeEventType.MAP_CAMERA_IS_CHANGING,
      RuntimeEventType.MAP_CAMERA_DID_CHANGE -> {
        // The transform does not change between the events of one drain, so the first camera
        // event of a drain marks the mirror stale for all of them.
        if (!cameraEventInDrain) {
          cameraEventInDrain = true
          viewport.snapshotStale = true
        }
        if (lease != null && mapEvent != null) {
          if (viewport.snapshotStale) loop.map?.let(viewport::snapshot)
          events.presentationEvent(engine, lease, mapEvent)
        }
      }

      RuntimeEventType.MAP_CAMERA_TRANSITION_FINISHED -> {
        val payload = event.payload
        if (payload !is RuntimeEventPayload.CameraTransitionFinished) {
          logger?.w { "A camera transition finished without a payload naming it" }
        } else {
          cameraTransitions.finished(payload.transitionId)
        }
      }

      RuntimeEventType.MAP_RENDER_FRAME_FINISHED ->
        if (lease != null && mapEvent != null) events.presentationEvent(engine, lease, mapEvent)

      RuntimeEventType.MAP_RENDER_ERROR ->
        logger?.e { "MapLibre render error: ${event.message.ifBlank { "unknown" }}" }

      // mbgl re-checks its image set at the next placement after setStyleImage, so a resolution
      // that finishes after this drain still reaches a later frame.
      RuntimeEventType.MAP_STYLE_IMAGE_MISSING ->
        styleBinding?.identity?.let { events.resolveMissingImage(it, event.message) }

      // Event types are value classes over Int, so an FFI upgrade can add one this build has never
      // seen. Types this session does not select are never queued.
      else -> logger?.d { "Unrecognized MapLibre event type ${event.type}" }
    }
  }

  private fun imageScale(): Float = loop.scaleFactor.toFloat()

  // endregion

  // region dispatch

  // Off the owner thread, the map is reached through [loop]. MlnFfiMapRuntimeLoop's documentation
  // says which of its operations to use.

  private fun applyPendingViewport(map: MapHandle) {
    if (
      viewport.applyPending(
        map,
        beforeResize = { size -> cameraTransitions.viewportChanged(map, size) },
        beforePadding = { cameraTransitions.cancelAnchor(map) },
      )
    ) {
      notifyViewportChanged()
    }
  }

  private fun recordCamera(position: CameraPosition, guard: CameraCommandGuard?) {
    if (guard?.isValid() == false) return
    requestedCamera = position
    loop.submit { map ->
      if (guard?.isValid() == false) return@submit
      val applied = viewport.cameraForAssignment(position)
      map.jumpTo(applied.toCameraOptions(viewport.appliedViewportInsets))
      viewport.snapshot(map)
    }
  }

  // endregion

  // region MapAdapter

  override fun setBaseStyle(style: BaseStyle) {
    if (style == requestedStyle) return
    requestedStyle = style
    val request = styleLoadTracker.request()
    // After the request, so a load the owner thread claimed earlier cannot install a live binding
    // after this read: the owner re-checks its request once it has installed the binding.
    styleBinding?.invalidate()
    // Disposes the composition holding the old style's sources and layers, which would otherwise
    // be validated against the base layers being replaced.
    if (lifecycleEngineIdentity != null) events.styleRequested(request)
    // Invalidation calls application code. A nested assignment owns the newer request.
    if (styleLoadTracker.requestId !== request || isClosing) return
    requestedStyleLoad = RequestedStyleLoad(style, request)
    // Wake the owner loop, but do not replace native until its preceding events are handled.
    loop.submit {}
  }

  override suspend fun <T> reconcileStyleRevision(
    revision: StyleSnapshot,
    capture: (StyleBinding) -> T,
  ): T {
    val binding = checkNotNull(styleBinding)
    try {
      val prepared = styleReconciler.prepare(binding, revision)
      return checkNotNull(
        loop.await {
          styleReconciler.apply(binding, prepared)
          val resources = capture(binding)
          if (!styleLoadTracker.contentReady && styleLoadTracker.reconciled(binding.identity)) {
            events.styleReady(binding.identity)
          }
          resources
        }
      ) {
        "The map became unavailable during style reconciliation"
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      styleLoadTracker.failed(binding.identity)
      throw error
    }
  }

  /** Owner thread only. */
  private fun applyRequestedStyle(map: MapHandle) {
    val load = requestedStyleLoad ?: return
    val style = load.style
    if (isClosing || lifecycleEngineIdentity == null) return
    val request = styleLoadTracker.requestId
    if (load.trackerRequest != request || appliedStyleRequest == request) return
    // Only bootstrap and the end of an event drain may replace the applied request. Native retires
    // the old document request in the setter; its queued response cannot run after that
    // retirement.
    appliedStyleRequest = request
    // A malformed JSON document queues a failure and throws. Argument rejection can throw before
    // native retires the old document, with no event. Report either once; the failed request then
    // rejects the old document's late success.
    try {
      when (style) {
        is BaseStyle.Uri -> map.setStyleUrl(style.uri)
        is BaseStyle.Json -> map.setStyleJson(style.json.encodeToByteArray())
      }
    } catch (error: MaplibreException) {
      val reason = error.message ?: "Failed to apply the base style"
      if (styleLoadTracker.failed(request) && !isClosing) {
        events.styleFailed(request, reason)
        logger?.e(error) { "Failed to apply style $style" }
        events.styleRequestEvent(request, MapEvent.StyleLoadFailed(reason))
      }
    }
  }

  private class RequestedStyleLoad(val style: BaseStyle, val trackerRequest: StyleRequestId)

  /** Applied when a map is created. Getters read [viewport] after native applies it. */
  @Volatile private var requestedCamera: CameraPosition? = null

  /** Owner thread only. Publish newly available or changed viewport geometry. */
  private fun notifyViewportChanged() {
    // The first attach snapshot can land before the lease is Attached. Seed from the snapshot
    // itself so a dropped camera callback cannot leave MapState.viewport null.
    lifecycleAuthority.seedCurrentPresentationViewport(this)
    withLifecyclePresentation { engine, lease -> events.viewportChanged(engine, lease) }
  }

  /** Owner thread only. True from the first camera event of a drain until the drain ends. */
  private var cameraEventInDrain = false

  override fun getCameraPosition(): CameraPosition = viewport.camera

  override fun setCameraPosition(cameraPosition: CameraPosition, guard: CameraCommandGuard?) {
    recordCamera(cameraPosition, guard)
  }

  override fun setViewportInsets(insets: PaddingValues) {
    val resolved = insets.toEdgeInsets(layoutDirection)
    if (!viewport.setInsets(resolved)) return
    loop.submit { map ->
      if (viewport.hasViewport) {
        viewport.applyInsets(map) { cameraTransitions.cancelAnchor(map) }
        viewport.snapshot(map)
      }
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
      loop.await { map ->
        cameraForBounds(map, boundingBox, bearing, pitch, cameraPadding, fitPadding)
          .toCameraPosition(viewport.appliedViewportInsets)
      }
    ) {
      "The map became unavailable during the bounds query"
    }

  override suspend fun cameraForGeometry(
    geometry: Geometry,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition {
    val geoJson = geometry.toJson().encodeToByteArray()
    return checkNotNull(
      loop.await { map ->
        fitCamera(map, bearing, pitch, cameraPadding, fitPadding) {
            map.cameraForGeometry(geoJson, it)
          }
          .toCameraPosition(viewport.appliedViewportInsets)
      }
    ) {
      "The map became unavailable during the geometry query"
    }
  }

  override suspend fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    guard: CameraCommandGuard?,
  ) {
    if (guard?.isValid() == false) return
    val fit: (MapHandle) -> Unit = fit@{ map ->
      if (guard?.isValid() == false) return@fit
      map.jumpTo(cameraForBounds(map, boundingBox, bearing, pitch, cameraPadding, fitPadding))
      viewport.snapshot(map)
    }
    // MapState waits for the current attachment's viewport before it calls this adapter.
    check(viewport.hasViewport && !isClosing) {
      "A bounds fit requires the current presentation viewport"
    }
    check(loop.await(action = fit) != null) { "The map became unavailable during the bounds fit" }
  }

  private fun cameraForBounds(
    map: MapHandle,
    boundingBox: BoundingBox,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraOptions =
    fitCamera(map, bearing, pitch, cameraPadding, fitPadding) {
      map.cameraForLatLngBounds(bounds = boundingBox.toLatLngBounds(), fitOptions = it)
    }

  private inline fun fitCamera(
    map: MapHandle,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    fit: (CameraFitOptions) -> CameraOptions,
  ): CameraOptions {
    val current = map.camera.toCameraPosition(viewport.appliedViewportInsets)
    val destination = current.copy(padding = cameraPadding ?: current.padding)
    val persistent =
      checkNotNull(destination.toCameraOptions(viewport.appliedViewportInsets).padding)
    val total = persistent + fitPadding.toEdgeInsets()
    val fitted =
      fit(
        CameraFitOptions().also {
          it.padding = total
          it.bearing = bearing
          it.pitch = pitch
        }
      )

    // Native returns the fit padding as persistent camera state. Preserve the fitted transform
    // while retaining only the viewport insets and destination camera padding.
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

  override suspend fun animateCamera(
    update: CameraUpdate,
    animation: CameraAnimation,
    guard: CameraCommandGuard?,
  ) {
    startTransitionAwaitingRelease(animation.toAnimationOptions(), guard = guard) { map, options ->
      map.animateTo(update.toCameraOptions(viewport.appliedViewportInsets), animation, options)
    }
  }

  override suspend fun animateCameraAround(
    anchor: CameraAnchor,
    zoom: Double?,
    bearing: Double?,
    pitch: Double?,
    animation: CameraAnimation.Ease,
    guard: CameraCommandGuard?,
  ) {
    startTransitionAwaitingRelease(
      animation.toAnimationOptions(),
      guard = guard,
      anchored = true,
    ) { map, options ->
      val size = map.size
      val point =
        map.createWrappedProjection().use { projection ->
          anchor.resolveScreenPoint(
            DpSize(size.width.dp, size.height.dp),
            centerLongitude = checkNotNull(projection.camera.center).longitude,
            project = { projection.pixelForLatLng(it.toLatLng()).toDpOffset() },
            unproject = { projection.latLngForPixelUnwrapped(it.toScreenPoint()).toPosition() },
          )
        }
      map.easeTo(
        CameraOptions().also {
          it.anchor = point.toScreenPoint()
          it.zoom = zoom
          it.bearing = bearing
          it.pitch = pitch
        },
        options,
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
    check(viewport.hasViewport) {
      "A bounds animation requires the current presentation viewport"
    }
    startTransitionAwaitingRelease(animation.toAnimationOptions(), guard = guard) { map, options ->
      map.animateTo(
        cameraForBounds(map, boundingBox, bearing, pitch, cameraPadding, fitPadding),
        animation,
        options,
      )
    }
  }

  /** Owner thread only. */
  private fun MapHandle.animateTo(
    camera: CameraOptions,
    animation: CameraAnimation,
    options: AnimationOptions,
  ) {
    when (animation) {
      is CameraAnimation.Ease -> easeTo(camera, options)
      is CameraAnimation.Fly ->
        flyTo(camera, options.copy { minZoom = flightMinZoom(camera, animation.minZoom) })
    }
  }

  /**
   * The minimum zoom to pass to `flyTo`, or null to leave the flight path alone.
   *
   * MapLibre GL JS fits the flight curve to the higher of the requested minimum and the map's
   * minimum zoom, and only when the natural path would pass below it. MapLibre Native fits the
   * curve to the requested minimum whenever one is given, zooming out to reach it, and otherwise
   * ignores the map's minimum until it clamps each frame. This mirrors the GL JS decision, using
   * the setup of `Transform::flyTo`.
   */
  private fun MapHandle.flightMinZoom(camera: CameraOptions, minZoom: Double?): Double? {
    val current = this.camera
    val size = size
    val padding = camera.padding ?: current.padding ?: EdgeInsets(0.0, 0.0, 0.0, 0.0)
    val startZoom = current.zoom ?: return null
    val start = current.center ?: return null
    val end = camera.center ?: start
    val mapMinZoom = bounds.minZoom ?: 0.0
    val zoomRange = mapMinZoom..(bounds.maxZoom ?: MaxNativeZoom)
    val zoom = (camera.zoom ?: startZoom).coerceIn(zoomRange)
    val floor = maxOf(minZoom ?: mapMinZoom, mapMinZoom)
    val peakZoom = minOf(floor, startZoom, zoom).coerceIn(zoomRange)
    val pathLength =
      mercatorPixelDistance(startZoom, start.toPosition(), end.toPosition()).takeIf { it > 0.0 }
        ?: return null
    // Screenfuls in pixels at the start scale: the visible span now and at the peak.
    val startSpan =
      maxOf(
        size.width - padding.left - padding.right,
        size.height - padding.top - padding.bottom,
      )
    val peakSpan = startSpan / 2.0.pow(peakZoom - startZoom)
    return floor.takeIf { sqrt(peakSpan / pathLength * 2.0) < FlightCurve }
  }

  private fun CameraAnimation.toAnimationOptions(): AnimationOptions =
    AnimationOptions().also {
      it.easing = UnitBezier(easing.x1, easing.y1, easing.x2, easing.y2)
      when (this) {
        is CameraAnimation.Ease -> it.durationMs = duration.inWholeMilliseconds.toDouble()
        is CameraAnimation.Fly -> {
          it.durationMs = duration?.inWholeMilliseconds?.toDouble()
          it.velocity = speed ?: CameraAnimation.Fly.DefaultSpeed
        }
      }
    }

  /** Resumes normally however the transition ended. */
  private suspend fun startTransitionAwaitingRelease(
    animation: AnimationOptions,
    gestureToken: CameraInputToken? = null,
    guard: CameraCommandGuard? = null,
    anchored: Boolean = false,
    shouldStart: (MapHandle) -> Boolean = { true },
    start: (MapHandle, AnimationOptions) -> Unit,
  ): Unit = suspendCancellableCoroutine { continuation ->
    val release = { if (continuation.isActive) continuation.resume(Unit) }
    val enqueue = {
      if (isClosing) {
        release()
      } else {
        // Ordered: draining retires superseded anchor IDs before a later geometry command can
        // cancel them. A start that throws emits no event, so onDropped resumes the caller then.
        loop.submit(ordered = true, onDropped = release) { map ->
          val started =
            continuation.isActive &&
              runCameraCommand(
                gestureToken,
                guard,
                activate = { gestureToken?.let { activateGesture(map, it) } },
              ) {
                if (shouldStart(map)) {
                  cameraTransitions.start(map, animation, continuation, anchored, start)
                } else release()
              }
          if (!started) release()
        }
      }
    }
    if (guard?.isValid() == false || gestureToken != null && !gestureToken.enqueue(enqueue)) {
      release()
    } else if (gestureToken == null) enqueue()
    guard?.dispatched()
  }

  override fun getCameraConstraints(): CameraConstraints = cameraConstraints ?: CameraConstraints()

  override fun setCameraConstraints(value: CameraConstraints) {
    if (value == cameraConstraints) return
    cameraConstraints = value
    loop.submit { map ->
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

  override fun setRenderSettings(value: RenderOptions) {
    if (maximumFps != value.maximumFps) {
      maximumFps = value.maximumFps
      presentation.requestRender()
    }
    val cameraProjectionChanged = cameraProjection != value.cameraProjection
    cameraProjection = value.cameraProjection
    loop.submit { map ->
      map.debugOptions = buildSet {
        if (value.debug.tileBorders) add(DebugOption.TILE_BORDERS)
        if (value.debug.tileTimestamps) add(DebugOption.TIMESTAMPS)
        if (value.debug.collisionBoxes) add(DebugOption.COLLISION)
        if (value.debug.tileParseStatus) add(DebugOption.PARSE_STATUS)
      }
      if (cameraProjectionChanged) {
        map.projectionMode = value.cameraProjection.toFfi()
        viewport.snapshot(map)
        notifyViewportChanged()
      }
    }
  }

  override fun setTileLodSettings(value: TileLodOptions) {
    if (value == tileLodOptions) return
    tileLodOptions = value
    loop.submit { map -> map.tileOptions = value.toFfi() }
  }

  /** Main thread. Returns the engine for [withPlatformMap], creating it if none exists. */
  internal suspend fun ensureEngine(): EngineMapIdentity = lifecycle.ensureEngine()

  /** Runs [block] on the owner thread of [engine], the engine that [ensureEngine] returned. */
  internal suspend fun <T> withPlatformMap(
    engine: EngineMapIdentity,
    block: PlatformMapScope.() -> T,
  ): T {
    val changed = "The native platform map changed before access could begin"
    return suspendCancellableCoroutine { continuation ->
      val invocation = PlatformMapInvocation(continuation)
      continuation.invokeOnCancellation { invocation.cancel() }
      if (isClosing) {
        invocation.fail(CancellationException("The map state closed before access could begin"))
      } else {
        loop.submit(
          onDropped = { invocation.fail(CancellationException(changed)) },
          action = { map ->
            invocation.executeGated(
              changed,
              // Runs on the owner thread: reads the volatile identity, not main-thread stamps.
              lifecycleGate = { event ->
                (!isClosing && lifecycleEngineIdentity == engine).also { if (it) event() }
              },
              authorityGate = { lifecycleAuthority.acceptEnginePlatformAccess(this, it) },
            ) {
              // Raw access can change the transform without an event, so the mirror is refreshed
              // when this drain ends.
              viewport.snapshotStale = true
              PlatformMapScope(map).block()
            }
          },
        )
      }
    }
  }

  override suspend fun fitBoundsAwaitingTransition(
    fit: BoxZoomFit,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) {
    if (!acceptsGestures) return
    startTransitionAwaitingRelease(duration.toAnimationOptions(), gestureToken = gestureToken) {
      map,
      animation ->
      val camera = cameraForBounds(map, fit.bounds, fit.bearing, fit.pitch, null, DpPadding.Zero)
      map.easeTo(camera, animation)
    }
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
  ): List<Feature<Geometry, JsonObject?>> = query(rect.toQueryGeometry(), layerIds, predicate)

  /** Runs every layer's query in one render session visit instead of one visit per layer. */
  override suspend fun queryRenderedFeaturesByLayer(
    offset: DpOffset,
    hitPadding: Map<String, Dp>,
  ): Map<String, List<Feature<Geometry, JsonObject?>>> =
    awaitRenderSession { session ->
      hitPadding.mapValues { (id, padding) ->
        val geometry =
          if (padding == 0.dp) RenderedQueryGeometry.Point(offset.toScreenPoint())
          else
            DpRect(offset.x - padding, offset.y - padding, offset.x + padding, offset.y + padding)
              .toQueryGeometry()
        session.query(geometry, setOf(id), null)
      }
    } ?: hitPadding.mapValues { emptyList() }

  private fun DpRect.toQueryGeometry(): RenderedQueryGeometry =
    RenderedQueryGeometry.Box(
      ScreenBox(
        min = DpOffset(left, top).toScreenPoint(),
        max = DpOffset(right, bottom).toScreenPoint(),
      )
    )

  /** Rendered feature state belongs to the render session, so a query without one is empty. */
  private suspend fun query(
    geometry: RenderedQueryGeometry,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    awaitRenderSession { session -> session.query(geometry, layerIds, predicate) } ?: emptyList()

  private fun RenderSessionHandle.query(
    geometry: RenderedQueryGeometry,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> =
    queryRenderedFeatures(geometry, renderedQueryOptions(layerIds, predicate))
      .toGeoJsonFeatures()
      // Native walks style layers from the bottom. MapState and GL JS put the feature in front
      // first.
      .asReversed()

  override fun getVisibleBounds() = viewport.getVisibleBounds()

  override fun getVisibleRegion() = viewport.getVisibleRegion()

  override fun getViewport() = viewport.getViewport()

  override fun positionFromScreenLocation(offset: DpOffset) =
    viewport.positionFromScreenLocation(offset)

  override fun boxZoomFit(rect: DpRect) = viewport.boxZoomFit(rect)

  override fun screenLocationFromPosition(position: Position) =
    viewport.screenLocationFromPosition(position)

  override fun overlayScreenLocationFromPosition(position: Position) =
    viewport.overlayScreenLocationFromPosition(position)

  override fun metersPerDpAtLatitude(latitude: Double) = viewport.metersPerDpAtLatitude(latitude)

  override suspend fun <T> awaitRenderSession(action: (RenderSessionHandle) -> T): T? =
    presentation.awaitRenderSession(action)

  // endregion

  // region input, called from Compose

  override val isGestureReady: Boolean
    get() = canPresentFrames && loop.failure == null && viewport.hasViewport

  override fun observeInput(): Long = lifecycleAuthority.gestureCamera.observeInput()

  override val inputGeneration: Long
    get() = lifecycleAuthority.gestureCamera.generation

  override fun onGestureStartedIfCurrent(generation: Long): CameraInputToken? =
    lifecycleAuthority.gestureCamera.acquireIfCurrent(this, generation)

  override fun onGestureStarted(): CameraInputToken =
    lifecycleAuthority.gestureCamera.acquire(this).also { token ->
      // Recognition takes over an existing transition even before the first movement.
      submitCameraInput(token) {}
    }

  override fun onGestureEnded(token: CameraInputToken) = finishGesture(token, cancelled = false)

  override fun cancelGesture(token: CameraInputToken) = finishGesture(token, cancelled = true)

  private fun finishGesture(token: CameraInputToken, cancelled: Boolean) {
    token.finish(cancelled) {
      // Ordered: the fence completes after the events of the gesture's last command.
      loop.submit(ordered = true, onDropped = token::complete) { map ->
        if (activeGestureToken === token) {
          if (token.isCancelled) map.cancelTransitions()
          pendingGestureEndToken = token
        }
        gestureFences += token
        // Without a lease, onEventsDrained does not publish camera observations.
        if (ownerThreadRenderLease == null) finishPendingGesture(map)
      }
    }
  }

  /**
   * Owner thread only. Reports on every camera command, because a report made before the lease
   * attaches is dropped.
   */
  private fun activateGesture(map: MapHandle, token: CameraInputToken) {
    val active = activeGestureToken
    if (active != token) {
      map.cancelTransitions()
      activeGestureToken = token
      pendingGestureEndToken = null
      map.isGestureInProgress = true
    }
    reportGestureActive(true)
  }

  /** Runs once the runtime event queue is momentarily empty. Owner thread only. */
  private fun finishPendingGesture(map: MapHandle) {
    val token = pendingGestureEndToken
    pendingGestureEndToken = null
    if (token != null && activeGestureToken === token) {
      activeGestureToken = null
      map.isGestureInProgress = false
      reportGestureActive(false)
    }
    val completing = gestureFences.toList()
    gestureFences.clear()
    completing.forEach { it.complete() }
  }

  /**
   * Owner thread only, so the fact keeps program order with the camera events this thread drains.
   * Reported from the UI thread instead, a gesture end would land before the queued move it ends.
   */
  private fun reportGestureActive(active: Boolean) {
    val engine = lifecycleEngineIdentity ?: return
    val lease = ownerThreadRenderLease ?: return
    events.gestureActive(engine, lease, active)
  }

  private fun onEventsDrained(map: MapHandle) {
    // Cleared first: a failure below must not leave the next drain unable to mark the mirror stale.
    cameraEventInDrain = false
    applyPendingViewport(map)
    if (viewport.snapshotStale && ownerThreadRenderLease != null) viewport.snapshot(map)
    // A detached presentation cannot publish events, but accepted command fences still finish.
    finishPendingGesture(map)
    cameraTransitions.eventsDrained()
    // Apply once per drain, including while detached. Events already in this batch belong to the
    // preceding producer; setters requested by callbacks cannot change their attribution midway.
    applyRequestedStyle(map)
  }

  /**
   * Before the presentation is visible the map has only its bootstrap viewport, and a camera
   * command projected through it jumps the camera. Gestures are dropped until then.
   */
  private val acceptsGestures: Boolean
    get() = canPresentFrames

  private fun submitCameraInput(gestureToken: CameraInputToken?, action: (MapHandle) -> Unit) {
    if (!acceptsGestures) return
    val enqueue = {
      loop.submit { map ->
        if (acceptsGestures)
          runCameraCommand(
            gestureToken,
            activate = { gestureToken?.let { activateGesture(map, it) } },
          ) {
            action(map)
          }
      }
    }
    if (gestureToken == null) enqueue() else gestureToken.enqueue(enqueue)
  }

  override fun moveBy(deltaX: Double, deltaY: Double, gestureToken: CameraInputToken?) {
    submitCameraInput(gestureToken) { map -> map.moveBy(deltaX, deltaY) }
  }

  override suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) {
    if (!acceptsGestures) return
    startTransitionAwaitingRelease(duration.toAnimationOptions(), gestureToken = gestureToken) {
      map,
      animation ->
      map.moveByAnimated(deltaX, deltaY, animation)
    }
  }

  override fun scaleBy(scale: Double, anchor: DpOffset?, gestureToken: CameraInputToken?) {
    submitCameraInput(gestureToken) { map -> map.scaleBy(scale, anchor?.toScreenPoint()) }
  }

  override suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) {
    if (!acceptsGestures) return
    startTransitionAwaitingRelease(duration.toAnimationOptions(), gestureToken = gestureToken) {
      map,
      animation ->
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
    anchor: DpOffset?,
    gestureToken: CameraInputToken?,
    feedback: Boolean,
  ) {
    // The read and the write must happen together on the owner thread.
    submitCameraInput(gestureToken) { map ->
      val camera = map.camera
      val target =
        CameraOptions().also {
          it.bearing = (camera.bearing ?: 0.0) + bearingDelta
          it.pitch = ((camera.pitch ?: 0.0) + pitchDelta).coerceIn(MinPitchDegrees, MaxPitchDegrees)
          it.anchor = anchor?.toScreenPoint()
        }
      map.jumpTo(target)
      if (feedback && bearingDelta != 0.0) {
        gestureToken?.reportRotation(camera.bearing ?: 0.0, map.camera.bearing ?: 0.0)
      }
    }
  }

  override suspend fun snapBearingAwaitingTransition(
    snapping: BearingSnapping,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) {
    if (!acceptsGestures) return
    var bearing = 0.0
    startTransitionAwaitingRelease(
      duration.toAnimationOptions(),
      gestureToken = gestureToken,
      shouldStart = { map ->
        val current = map.camera.bearing ?: 0.0
        snapping.delta(current)?.let {
          bearing = current + it
          true
        } ?: false
      },
    ) { map, animation ->
      map.easeTo(CameraOptions().also { it.bearing = bearing }, animation)
    }
  }

  override suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: CameraInputToken,
    anchor: DpOffset?,
  ) {
    if (!acceptsGestures) return
    startTransitionAwaitingRelease(duration.toAnimationOptions(), gestureToken = gestureToken) {
      map,
      animation ->
      val camera = map.camera
      map.easeTo(
        CameraOptions().also {
          it.anchor = anchor?.toScreenPoint()
          it.bearing = (camera.bearing ?: 0.0) + bearingDelta
          it.pitch = ((camera.pitch ?: 0.0) + pitchDelta).coerceIn(MinPitchDegrees, MaxPitchDegrees)
        },
        animation,
      )
    }
  }

  private inline fun withLifecyclePresentation(action: (EngineMapIdentity, RenderLease) -> Unit) {
    val engine = lifecycleEngineIdentity ?: return
    val lease = lifecycleRenderLease ?: return
    action(engine, lease)
  }

  override fun interruptCamera() {
    stopCameraMovement(lifecycleAuthority.gestureCamera.beginProgrammatic())
  }

  override fun stopCameraMovement(guard: CameraCommandGuard) {
    if (!guard.isValid()) return
    loop.submit { map ->
      if (!guard.isValid()) return@submit
      map.cancelTransitions()
    }
  }
  // endregion
}

private fun TileLodOptions.toFfi(): TileOptions = algorithm.toFfi()

private fun CameraProjection.toFfi(): ProjectionModeOptions =
  ProjectionModeOptions().also { options ->
    when (this) {
      CameraProjection.Perspective -> options.axonometric = false
      is CameraProjection.Axonometric -> {
        options.axonometric = true
        options.xSkew = xSkew
        options.ySkew = ySkew
      }
    }
  }
