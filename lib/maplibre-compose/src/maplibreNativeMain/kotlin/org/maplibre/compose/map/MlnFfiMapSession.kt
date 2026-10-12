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
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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
import org.maplibre.compose.camera.internal.runCameraCommand
import org.maplibre.compose.camera.resolveScreenPoint
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.interaction.BearingSnapping
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.CommandFailedException
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameProjection
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiMapRenderer
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.failed
import org.maplibre.compose.mlnffi.failureMessage
import org.maplibre.compose.mlnffi.reportFailure
import org.maplibre.compose.mlnffi.use
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LoadedStyleContents
import org.maplibre.compose.style.MlnFfiRenderSessions
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleLoadTracker
import org.maplibre.compose.style.StylePresentation
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.StyleRequestId
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.style.UnspecifiedBaseStyle
import org.maplibre.compose.util.DelicateMaplibreComposeApi
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
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
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.error.MaplibreStatus
import org.maplibre.nativeffi.generated.AnimationOptions
import org.maplibre.nativeffi.generated.BoundOptions
import org.maplibre.nativeffi.generated.CameraDelta
import org.maplibre.nativeffi.generated.CameraFitOptions
import org.maplibre.nativeffi.generated.CameraOptions
import org.maplibre.nativeffi.generated.CameraUpdate as FfiCameraUpdate
import org.maplibre.nativeffi.generated.CameraUpdateMode
import org.maplibre.nativeffi.generated.EdgeInsets
import org.maplibre.nativeffi.generated.GeneratedApi
import org.maplibre.nativeffi.generated.GesturePhase
import org.maplibre.nativeffi.generated.LogicalExtent
import org.maplibre.nativeffi.generated.MapDebugOption
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.MapMode
import org.maplibre.nativeffi.generated.MapTileOptions
import org.maplibre.nativeffi.generated.ProjectionMode
import org.maplibre.nativeffi.generated.QueriedFeature
import org.maplibre.nativeffi.generated.RenderSessionHandle
import org.maplibre.nativeffi.generated.RenderedQueryGeometry
import org.maplibre.nativeffi.generated.RenderedQueryGeometryData
import org.maplibre.nativeffi.generated.RuntimeEvent
import org.maplibre.nativeffi.generated.RuntimeEventMask
import org.maplibre.nativeffi.generated.RuntimeEventType
import org.maplibre.nativeffi.generated.ScreenBox
import org.maplibre.nativeffi.generated.ScreenPoint
import org.maplibre.nativeffi.generated.UnitBezier
import org.maplibre.nativeffi.runtime.CommandCompletion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

private const val MinPitchDegrees = 0.0

/** MapLibre rejects a pitch beyond this, so an animated pitch is clamped rather than refused. */
private const val MaxPitchDegrees = 60.0

/** The events [MlnFfiMapSession.handleEvent] consumes. */
private val HandledMapEvents: RuntimeEventMask =
  RuntimeEventMask.MAP_RENDER_UPDATE_AVAILABLE or
    RuntimeEventMask.MAP_STYLE_LOADED or
    RuntimeEventMask.MAP_IDLE or
    RuntimeEventMask.MAP_LOADING_FAILED or
    RuntimeEventMask.MAP_CAMERA_WILL_CHANGE or
    RuntimeEventMask.MAP_CAMERA_IS_CHANGING or
    RuntimeEventMask.MAP_CAMERA_DID_CHANGE or
    RuntimeEventMask.MAP_RENDER_ERROR or
    RuntimeEventMask.MAP_RENDER_FRAME_FINISHED or
    RuntimeEventMask.MAP_STYLE_IMAGE_MISSING

/**
 * One logical MapLibre Native map. Commands and event delivery run on main; native orders the
 * commands. The render session belongs to the host's graphics thread. NativePresentation owns that
 * attachment and NativeViewport reads camera and extent snapshots.
 */
internal class MlnFfiMapSession(
  private val lifecycleAuthority: MapLifecycleAuthority,
  callbacks: MapAdapter.Callbacks,
  @Volatile internal var logger: MapLog?,
  renderBackend: MapRenderBackend,
  scaleFactor: Double = 1.0,
  @Volatile internal var layoutDirection: LayoutDirection,
  private val owner: MlnFfiRuntime,
) : MapAdapter, MlnFfiMapRenderer, MlnFfiRenderSessions, CameraInputTarget {

  @Volatile internal var callbacks: MapAdapter.Callbacks = callbacks
  @Volatile internal var durableCallbacks: MapAdapter.Callbacks = EmptyMapAdapterCallbacks
  @Volatile private var closing = false
  private val closure = CompletableDeferred<Result<Unit>>()
  private var attaching: CompletableDeferred<Result<Unit>>? = null
  private var lastDetach: CompletableDeferred<Result<Unit>>? = null
  @Volatile private var presentationActive = false

  override var backend: MapRenderBackend = renderBackend
    private set

  private var scaleFactor = scaleFactor

  /** The map, once created and until destroyed. */
  @Volatile private var map: MapHandle? = null

  /** Test seam: the map, once created and until destroyed. */
  internal val mapForTest: MapHandle?
    get() = map

  /** Test seam: the host session the map presents into, if any. */
  internal val presentationHostForTest: MlnFfiMapHostSession?
    get() = presentation.host

  private val viewport = NativeViewport()
  internal val presentation: NativePresentation =
    NativePresentation(
      backend = backend,
      isClosing = { lifecycleAuthority.isClosed || isClosing },
      canRender = { !isClosing && presentationActive },
      getLogger = { logger },
      viewport = viewport,
      postToMain = owner::postToMain,
      onAttached = {
        owner.postToMain {
          val map = map ?: return@postToMain
          if (isClosing || !presentationActive) return@postToMain
          viewport.attach(map)
          cameraTransitions.viewportChanged(viewport.size)
          applyViewportInsets(map)
          notifyViewportChanged()
        }
      },
    )

  private val cameraTransitions =
    MlnFfiCameraTransitions(
      logger = { logger },
      postToMain = owner::postToMain,
      drain = owner::drain,
    )

  internal val canPresentFrames: Boolean
    get() = styleLoadTracker.presentation != StylePresentation.Hidden

  private var failureReported = false

  @Volatile private var requestedStyle: BaseStyle? = null
  private val styleLoadTracker = StyleLoadTracker()

  /** The current request and the completion carrying its native generation. Main only. */
  private var styleCompletion: Deferred<CommandCompletion>? = null
  private var activeGestureToken: CameraInputToken? = null
  private var gestureBearing: Double? = null
  private var gestureEndGeneration = 0uL
  private var gestureEnd: Deferred<CommandCompletion>? = null

  @Volatile private var styleBinding: MlnFfiStyleBinding? = null

  private val styleReconciler = StyleReconciler()

  internal val loadedStyleIdentity
    get() = styleBinding?.identity

  /**
   * URL sources whose TileJSON attribution has already been reported, so each is reported exactly
   * once rather than on every idle.
   */
  private val reportedUrlAttribution = mutableSetOf<String>()

  /**
   * Main only. Checks for attribution received through a URL source's TileJSON after addSource
   * returns. The C API has no TileJSON arrival event, so check on idle with one listing query.
   */
  private suspend fun reportNewlyArrivedAttribution(map: MapHandle) {
    val binding = styleBinding ?: return
    val sources = map.listStyleSources().await()
    if (styleBinding !== binding || !binding.isLoaded) return
    var changed = false
    for (info in sources) {
      if (
        !info.url.isNullOrEmpty() &&
          !info.attribution.isNullOrEmpty() &&
          reportedUrlAttribution.add(info.id)
      ) {
        changed = true
      }
    }
    if (changed) callbacks.onStyleSourcesChanged(this)
  }

  private suspend fun createStyleBinding(map: MapHandle): MlnFfiStyleBinding =
    MlnFfiStyleBinding(
      map = map,
      owner = owner,
      styleFailed = { identity, error ->
        if (styleBinding?.identity === identity) {
          styleLoadTracker.failed(identity)
          callbacks.onStyleFailed(this, error.message)
        }
      },
      contents = LoadedStyleContents.read(map),
      loggerProvider = { logger },
      sessionOpen = { !isClosing },
      renderSessions = this,
      sourceChanged = { sourceId ->
        owner.postToMain { reportedUrlAttribution.remove(sourceId) }
      },
      sourceDataFailed = { bindingIdentity, sourceId, error ->
        owner.postToMain {
          if (styleBinding?.identity === bindingIdentity) {
            callbacks.onEvent(this, MapEvent.SourceDataFailed(sourceId, error))
          }
        }
      },
      getScale = { scaleFactor.toFloat() },
    )

  @Volatile
  override var maximumFps: Int? = null
    private set

  @Volatile private var cameraConstraints: CameraConstraints? = null
  @Volatile private var renderSettings: RenderOptions? = null
  @Volatile private var cameraProjection: CameraProjection = CameraProjection.Perspective
  @Volatile private var tileLodOptions: TileLodOptions = TileLodOptions.Standard

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
    owner.failure?.let { error ->
      if (!failureReported) {
        failureReported = true
        close()
        throw IllegalStateException("The MapLibre map runtime failed", error)
      }
      return MlnFfiFrameResult.AwaitUpdate
    }
    val map = map ?: return MlnFfiFrameResult.AwaitUpdate
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

  internal val isClosing: Boolean
    get() = closing

  override fun close() = owner.postToMain {
    if (closing) return@postToMain
    closing = true
    lifecycleAuthority.sessionClosing(this)
    owner.launchOnMain {
      val failures = mutableListOf<Throwable>()
      attaching
        ?.await()
        ?.exceptionOrNull()
        ?.takeUnless { it is CancellationException }
        ?.let(failures::addCleanupFailure)
      runCatching { detachPresentation() }.exceptionOrNull()?.let(failures::addCleanupFailure)
      runCatching { stopMap() }.exceptionOrNull()?.let(failures::addCleanupFailure)
      runCatching { map?.cancelTransitions()?.report("cancel camera transitions") }
        .exceptionOrNull()
        ?.let(failures::addCleanupFailure)
      runCatching { retireOwnerState() }.exceptionOrNull()?.let(failures::addCleanupFailure)
      closure.complete(failures.cleanupResult("Map"))
    }
  }

  override suspend fun awaitClosed() = closure.await().getOrThrow()

  private fun isCurrentStyleRequest(request: StyleRequestId): Boolean =
    !isClosing && request === styleLoadTracker.requestId

  internal fun preparePresentation() {
    styleLoadTracker.resetPresentation()
  }

  internal val isPresentationPublished: Boolean
    get() = lifecycleAuthority.acceptsPresentation(this)

  internal fun beginPresentationAttachment(
    renderBackend: MapRenderBackend = backend,
    density: Double = scaleFactor,
  ): Boolean {
    if (isClosing || !lifecycleAuthority.selectAdapterForPresentation(this)) return false
    beginAttach(renderBackend, density)
    return true
  }

  private fun beginAttach(renderBackend: MapRenderBackend, density: Double) {
    if (attaching != null) return
    val completion = CompletableDeferred<Result<Unit>>()
    attaching = completion
    val precedingDetach = lastDetach
    owner.launchOnMain {
      completion.complete(
        runCatching {
          precedingDetach?.await()?.getOrThrow()
          if (isClosing) throw CancellationException("The map state is closed")
          backend = renderBackend
          presentation.backend = renderBackend
          if (map != null && scaleFactor != density) stopMap(keepHost = true)
          scaleFactor = density
          if (map == null) createMap()
          owner.drain()
          presentationActive = true
          presentation.resume()
        }
      )
    }
  }

  suspend fun attachPresentation(
    renderBackend: MapRenderBackend = backend,
    density: Double = scaleFactor,
  ) {
    if (isClosing) throw CancellationException("The map state is closed")
    beginAttach(renderBackend, density)
    checkNotNull(attaching).await().getOrThrow()
    if (isClosing || !presentationActive) throw CancellationException("The presentation ended")
  }

  internal fun publishRetainedStyle() {
    // The installed binding stays after a replacement is requested, and after that replacement
    // fails. Only the current request's style is republished.
    styleBinding?.let { callbacks.onStyleChanged(this, it) }
  }

  /**
   * Ends the current presentation. From here on the engine reports to the map state's durable
   * callbacks; the next presentation installs its own after it attaches.
   */
  override suspend fun detachPresentation() {
    callbacks = durableCallbacks
    val pendingAttach = attaching
    attaching = null
    presentationActive = false
    val completion = CompletableDeferred<Result<Unit>>()
    val previous = lastDetach
    lastDetach = completion
    owner.launchOnMain {
      completion.complete(
        runCatching {
          previous?.await()?.getOrThrow()
          pendingAttach?.await()
          map?.cancelTransitions()?.report("cancel camera transitions")?.await()
          owner.drain()
          viewport.detach()
          presentationActive = false
          presentation.release()
        }
      )
    }
    completion.await().getOrThrow()
  }

  private suspend fun createMap() {
    val runtime = owner.awaitRuntime()
    val created =
      runtime
        .createMap(
          GeneratedApi.mapOptionsDefault()
            .copy(
              // The bootstrap extent until a render target applies its own. The scale factor is
              // fixed for the map's lifetime.
              initialExtent = LogicalExtent(1u, 1u, scaleFactor),
              mapMode = MapMode.CONTINUOUS,
              eventMask = HandledMapEvents,
            )
        )
        .await()
    if (!owner.register(created, child)) {
      created.release().await()
      error("The map runtime is closed")
    }

    this@MlnFfiMapSession.map = created
    viewport.prepareCamera(requestedCamera)?.let { created.updateCamera(it).report("move") }
    cameraConstraints?.let { created.applyConstraints(it) }
    renderSettings?.let { created.applyRenderSettings(it, projectionChanged = true) }
    if (tileLodOptions != TileLodOptions.Standard)
      created.setTileOptions(tileLodOptions.toFfi()).report("set the tile options")

    requestedStyle?.let { applyRequestedStyle(created, it, styleLoadTracker.requestId) }
    presentation.requestRender()
  }

  /** MapLibre refuses to release a map that still has a render session attached. */
  internal suspend fun stopMap(keepHost: Boolean = false) {
    viewport.detach()
    if (styleBinding != null) {
      lifecycleAuthority.postToMain { callbacks.onStyleChanged(this, null) }
    }
    styleBinding?.invalidate()
    styleBinding = null
    styleLoadTracker.engineBecameUnavailable()
    // A frame queued behind this release sees the session closing in ensureAttached.
    presentation.release(keepHost = keepHost)
    val stopping = map.also { map = null }
    try {
      if (stopping != null) {
        owner.unregister(stopping)
        withContext(NonCancellable) {
          try {
            stopping.release().await()
          } catch (error: MaplibreException) {
            // A render session that failed to detach keeps the map alive; disposal lets it go
            // once the session retires.
            if (error.status != MaplibreStatus.INVALID_STATE || stopping.isClosed) throw error
            stopping.dispose()
          }
        }
      }
    } finally {
      retireOwnerState()
    }
  }

  /** Only after the map is unregistered, so drains no longer visit this state. */
  private fun retireOwnerState() {
    viewport.retire()
    activeGestureToken?.complete()
    activeGestureToken = null
    gestureBearing = null
  }

  // endregion

  // region main-thread events

  private val child =
    object : MlnFfiRuntime.MapChild {
      override fun onEvent(event: RuntimeEvent) {
        val map = map ?: return
        handleEvent(map, event)
      }
    }

  /** Runs on main, as do the callbacks it makes. */
  private fun handleEvent(map: MapHandle, event: RuntimeEvent) {
    // A wake can drain later commands before the END waiter resumes on main. Native has already
    // completed END before queuing a newer generation, so its completed result identifies where
    // gesture camera reporting stops within this batch.
    val ending = gestureEnd
    if (ending?.isCompleted == true) {
      @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
      val generation = runCatching { ending.getCompleted().generation }.getOrNull()
      if (generation != null) gestureEndGeneration = generation
      if (generation != null && event.generation > generation && activeGestureToken == null) {
        reportGestureActive(false)
      }
    }
    val mapEvent = event.toMapEvent()
    when (event.type) {
      RuntimeEventType.MAP_STYLE_LOADED -> {
        val request = styleLoadTracker.requestId
        val submitted = styleCompletion ?: return
        owner.launchOnMain {
          val completion = submitted.await()
          if (
            !isCurrentStyleRequest(request) ||
              completion.failed ||
              event.generation < completion.generation
          )
            return@launchOnMain
          val binding = createStyleBinding(map)
          if (
            !isCurrentStyleRequest(request) || !styleLoadTracker.loaded(request, binding.identity)
          ) {
            binding.invalidate()
            return@launchOnMain
          }
          styleBinding?.invalidate()
          styleBinding = binding
          reportedUrlAttribution.clear()
          callbacks.onStyleChanged(this, binding)
          mapEvent?.let { callbacks.onEvent(this, it) }
        }
      }

      RuntimeEventType.MAP_IDLE -> {
        if (styleLoadTracker.isReady && styleBinding != null) {
          owner.launchOnMain { reportNewlyArrivedAttribution(map) }
        }
        mapEvent?.let { callbacks.onEvent(this, it) }
      }

      RuntimeEventType.MAP_LOADING_FAILED -> {
        val request = styleLoadTracker.requestId
        val submitted = styleCompletion ?: return
        owner.launchOnMain {
          val completion = submitted.await()
          if (
            !isCurrentStyleRequest(request) ||
              completion.failed ||
              event.generation < completion.generation
          )
            return@launchOnMain
          val reason = event.styleLoadFailureReason()
          if (styleLoadTracker.failed(request)) {
            callbacks.onStyleFailed(this, reason)
            logger?.e { "Map loading failed (code ${event.code}): $reason" }
            mapEvent?.let { callbacks.onEvent(this, it) }
          }
        }
      }

      // A snapshot read after an event includes its transform change. Older events in the
      // same drain reuse the projection from that snapshot generation.
      RuntimeEventType.MAP_CAMERA_WILL_CHANGE,
      RuntimeEventType.MAP_CAMERA_IS_CHANGING,
      RuntimeEventType.MAP_CAMERA_DID_CHANGE -> {
        if (event.generation > viewport.generation) {
          val previousSize = viewport.size
          viewport.refresh(map)
          if (viewport.size != previousSize) {
            cameraTransitions.viewportChanged(viewport.size)
            if (viewport.hasViewport) notifyViewportChanged()
          }
        }
        if (presentationActive && mapEvent != null) callbacks.onEvent(this, mapEvent)
      }

      RuntimeEventType.MAP_RENDER_FRAME_FINISHED ->
        if (presentationActive && mapEvent != null) callbacks.onEvent(this, mapEvent)

      RuntimeEventType.MAP_RENDER_ERROR ->
        logger?.e { "MapLibre render error: ${event.message.ifBlank { "unknown" }}" }

      // mbgl re-checks its image set at the next placement after setStyleImage, so a resolution
      // that finishes after this drain still reaches a later frame.
      RuntimeEventType.MAP_STYLE_IMAGE_MISSING ->
        styleBinding?.let { callbacks.resolveMissingImage(this, event.message) }

      // Event types are open value classes, so an FFI upgrade can add one this build has never
      // seen. Types this session does not select are never queued.
      else -> logger?.d { "Unrecognized MapLibre event type ${event.type}" }
    }
  }

  private fun applyViewportInsets(map: MapHandle) {
    val update = viewport.applyInsets(map) { cameraTransitions.cancelAnchor() } ?: return
    val submitted = map.updateCamera(update).report("apply insets")
    owner.launchOnMain {
      submitted.await()
      owner.drain()
    }
  }

  /** Publishes newly available or changed viewport geometry. */
  private fun notifyViewportChanged() {
    // The first attach snapshot can land before presentation publication. Seed from the
    // snapshot
    // itself so a dropped camera callback cannot leave MapState.viewport null.
    lifecycleAuthority.seedCurrentPresentationViewport(this)
    if (presentationActive) callbacks.onViewportChanged(this)
  }

  // endregion

  // region MapAdapter

  override fun setBaseStyle(style: BaseStyle) {
    if (style == requestedStyle) return
    requestedStyle = style
    val request = styleLoadTracker.request()
    styleBinding?.invalidate()
    // Disposes the composition holding the old style's sources and layers, which would otherwise
    // be validated against the base layers being replaced.
    if (map != null) callbacks.onStyleChanged(this, null)
    // Invalidation calls application code. A nested assignment owns the newer request.
    if (styleLoadTracker.requestId !== request || isClosing) return
    map?.let { applyRequestedStyle(it, style, request) }
  }

  override suspend fun <T> reconcileStyleRevision(
    revision: StyleSnapshot,
    capture: suspend (StyleBinding) -> T,
  ): T {
    val binding = checkNotNull(styleBinding)
    try {
      val prepared = styleReconciler.prepare(binding, revision)
      binding.commandGroup { styleReconciler.apply(binding, prepared) }
      val resources = capture(binding)
      if (!binding.revisionFailed) {
        owner.postToMain {
          if (
            styleBinding === binding &&
              !binding.revisionFailed &&
              !styleLoadTracker.contentReady &&
              styleLoadTracker.reconciled(binding.identity)
          ) {
            callbacks.onStyleReady(this)
          }
        }
      }
      return resources
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      styleLoadTracker.failed(binding.identity)
      throw error
    }
  }

  private fun applyRequestedStyle(map: MapHandle, style: BaseStyle, request: StyleRequestId) {
    if (isClosing) return
    styleCompletion = null
    val submitted =
      try {
        styleBinding?.invalidate()
        when (style) {
          is BaseStyle.Uri -> map.setStyleUrl(style.uri)
          is BaseStyle.Json -> map.setStyleJson(style.json.encodeToByteArray())
          UnspecifiedBaseStyle -> error("UnspecifiedBaseStyle is never created")
        }
      } catch (error: MaplibreException) {
        styleRequestFailed(request, style, error.message ?: "Failed to apply the base style", error)
        return
      }
    styleCompletion = submitted
    owner.launchOnMain {
      val completion =
        try {
          submitted.await()
        } catch (error: Throwable) {
          if (error is CancellationException) throw error
          if (isCurrentStyleRequest(request))
            styleRequestFailed(
              request,
              style,
              error.message ?: "Failed to apply the base style",
              error,
            )
          return@launchOnMain
        }
      owner.drain()
      if (completion.failed && isCurrentStyleRequest(request)) {
        styleRequestFailed(request, style, completion.failureMessage(), null)
      }
    }
  }

  /** Reports a refused style request once; the failed request then rejects a late success. */
  private fun styleRequestFailed(
    request: StyleRequestId,
    style: BaseStyle,
    reason: String,
    error: Throwable?,
  ) {
    if (styleLoadTracker.failed(request) && !isClosing) {
      callbacks.onStyleFailed(this, reason)
      logger?.e(error) { "Failed to apply style $style: $reason" }
      callbacks.onEvent(this, MapEvent.StyleLoadFailed(reason))
    }
  }

  /** Applied when a map is created. Getters read [viewport] after native applies it. */
  @Volatile private var requestedCamera: CameraPosition? = null

  override fun getCameraPosition(): CameraPosition = viewport.camera

  override fun setCameraPosition(cameraPosition: CameraPosition, guard: CameraCommandGuard?) {
    if (guard?.isValid() == false) return
    requestedCamera = cameraPosition

    val map = map ?: return
    if (guard?.isValid() == false) return
    val applied = viewport.cameraForAssignment(cameraPosition)
    val submitted =
      map
        .updateCamera(
          FfiCameraUpdate(camera = applied.toCameraOptions(viewport.appliedViewportInsets))
        )
        .report("move the camera")
    owner.launchOnMain {
      submitted.await()
      owner.drain()
    }
  }

  override fun setViewportInsets(insets: PaddingValues) {
    val resolved = insets.toEdgeInsets(layoutDirection)
    if (!viewport.setInsets(resolved)) return

    val map = map ?: return
    if (!viewport.hasViewport) return
    applyViewportInsets(map)
  }

  override suspend fun cameraForBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition {
    val map = checkNotNull(map) { "The map became unavailable during the bounds query" }
    val insets = viewport.appliedViewportInsets
    return cameraForBounds(map, boundingBox, bearing, pitch, cameraPadding, fitPadding, insets)
      .toCameraPosition(insets)
  }

  override suspend fun cameraForGeometry(
    geometry: Geometry,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition {
    val map = checkNotNull(map) { "The map became unavailable during the geometry query" }
    val geoJson = geometry.toJson().encodeToByteArray()
    val insets = viewport.appliedViewportInsets
    return fitCamera(map, bearing, pitch, cameraPadding, fitPadding, insets) {
        map.cameraForGeometry(geoJson, it)
      }
      .toCameraPosition(insets)
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
    // MapState waits for the current attachment's viewport before it calls this adapter.
    check(viewport.hasViewport && !isClosing) {
      "A bounds fit requires the current presentation viewport"
    }
    val map = checkNotNull(map) { "The map became unavailable during the bounds fit" }
    val camera = cameraForBounds(map, boundingBox, bearing, pitch, cameraPadding, fitPadding)
    if (guard?.isValid() == false) return
    val completion = map.updateCamera(FfiCameraUpdate(camera = camera)).await()
    if (completion.failed) throw CommandFailedException(completion)
    owner.drain()
  }

  private suspend fun cameraForBounds(
    map: MapHandle,
    boundingBox: BoundingBox,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    insets: EdgeInsets = viewport.appliedViewportInsets,
  ): CameraOptions =
    fitCamera(map, bearing, pitch, cameraPadding, fitPadding, insets) {
      map.cameraForLatLngBounds(bounds = boundingBox.toLatLngBounds(), fitOptions = it)
    }

  internal suspend fun fitCamera(
    map: MapHandle,
    bearing: Double,
    pitch: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    insets: EdgeInsets = viewport.appliedViewportInsets,
    fit: (CameraFitOptions) -> Deferred<CameraOptions>,
  ): CameraOptions {
    val (persistent, fitted) =
      run {
        val current = map.getSnapshot().camera.toCameraPosition(insets)
        val destination = current.copy(padding = cameraPadding ?: current.padding)
        val persistent = checkNotNull(destination.toCameraOptions(insets).padding)
        val total = persistent + fitPadding.toEdgeInsets()
        persistent to fit(CameraFitOptions(padding = total, bearing = bearing, pitch = pitch))
      }
    val camera = fitted.await()
    if (this.map !== map || isClosing)
      throw CancellationException("The fitted map is no longer current")

    // Native returns the fit padding as persistent camera state. Preserve the fitted transform
    // while retaining only the viewport insets and destination camera padding.
    val center =
      map.createProjection().use { projection ->
        projection.setCamera(camera)
        val size = map.getSnapshot().logicalExtent
        projection.latLngForPixel(
          ScreenPoint(
            x = (size.width.toDouble() + persistent.left - persistent.right) / 2.0,
            y = (size.height.toDouble() + persistent.top - persistent.bottom) / 2.0,
          )
        )
      }
    return camera.copy(center = center, padding = persistent)
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
    startTransitionAwaitingRelease(
      animation.toAnimationOptions(),
      guard = guard,
      fields = update.fields(),
    ) { map, options ->
      map.updateCamera(
        FfiCameraUpdate(
          mode = animation.updateMode,
          camera = update.toCameraOptions(viewport.appliedViewportInsets),
          animation = options,
        )
      )
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
      fields =
        buildSet {
          if (zoom != null) add(TransitionField.Zoom)
          if (bearing != null) add(TransitionField.Bearing)
          if (pitch != null) add(TransitionField.Pitch)
        },
    ) { map, options ->
      val size = map.getSnapshot().logicalExtent
      val point =
        map.createWrappedProjection().use { projection ->
          anchor.resolveScreenPoint(
            DpSize(size.width.toInt().dp, size.height.toInt().dp),
            centerLongitude = checkNotNull(projection.getCamera().center).longitude,
            project = { projection.pixelForLatLng(it.toLatLng()).toDpOffset() },
            unproject = { projection.latLngForPixelUnwrapped(it.toScreenPoint()).toPosition() },
          )
        }
      map.updateCamera(
        FfiCameraUpdate(
          mode = CameraUpdateMode.EASE,
          camera =
            CameraOptions(
              anchor = point.toScreenPoint(),
              zoom = zoom,
              bearing = bearing,
              pitch = pitch,
            ),
          animation = options,
        )
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
    val camera = map?.let {
      cameraForBounds(it, boundingBox, bearing, pitch, cameraPadding, fitPadding)
    }
    startTransitionAwaitingRelease(animation.toAnimationOptions(), guard = guard) { target, options
      ->
      target.updateCamera(
        FfiCameraUpdate(
          mode = animation.updateMode,
          camera = checkNotNull(camera),
          animation = options,
        )
      )
    }
  }

  private val CameraAnimation.updateMode: CameraUpdateMode
    get() =
      when (this) {
        is CameraAnimation.Ease -> CameraUpdateMode.EASE
        is CameraAnimation.Fly -> CameraUpdateMode.FLY
      }

  private fun CameraAnimation.toAnimationOptions(): AnimationOptions =
    when (this) {
      is CameraAnimation.Ease ->
        AnimationOptions(
          durationMs = duration.inWholeMilliseconds.toDouble(),
          easing = UnitBezier(easing.x1, easing.y1, easing.x2, easing.y2),
        )
      is CameraAnimation.Fly ->
        AnimationOptions(
          durationMs = duration?.inWholeMilliseconds?.toDouble(),
          velocity = speed,
          minZoom = minZoom,
          easing = UnitBezier(easing.x1, easing.y1, easing.x2, easing.y2),
        )
    }

  /** Resumes normally however the transition ended. */
  private suspend fun startTransitionAwaitingRelease(
    animation: AnimationOptions,
    gestureToken: CameraInputToken? = null,
    guard: CameraCommandGuard? = null,
    anchored: Boolean = false,
    fields: Set<TransitionField> = TransitionField.entries.toSet(),
    shouldStart: (MapHandle) -> Boolean = { true },
    start: (MapHandle, AnimationOptions) -> Deferred<CommandCompletion>,
  ): Unit = suspendCancellableCoroutine { continuation ->
    val release = { if (continuation.isActive) continuation.resume(Unit) }
    val map = map
    if (isClosing || map == null) {
      release()
    } else {
      val started =
        continuation.isActive &&
          runCameraCommand(
            gestureToken,
            guard,
            activate = {
              gestureToken?.let {
                // A transition carries no gesture phase of its own, so a gesture that begins
                // with one marks itself with an empty update first.
                if (activateGesture(map, it) == GesturePhase.BEGIN) {
                  map
                    .updateCamera(FfiCameraUpdate(gesturePhase = GesturePhase.BEGIN))
                    .report("begin a gesture")
                }
              }
            },
          ) {
            if (shouldStart(map)) {
              cameraTransitions.start(map, animation, continuation, anchored, fields, start)
            } else release()
          }
      if (!started) release()
    }
  }

  override fun getCameraConstraints(): CameraConstraints =
    cameraConstraints ?: CameraConstraints.Standard

  override fun setCameraConstraints(value: CameraConstraints) {
    if (value == cameraConstraints) return

    cameraConstraints = value
    map?.applyConstraints(value)
  }

  private fun MapHandle.applyConstraints(value: CameraConstraints) {
    setBounds(
        BoundOptions(
          // Unbounded is not world bounds: world bounds clamp longitude to ±180 and stop the map
          // panning across the antimeridian.
          bounds = value.boundingBox?.toLatLngBounds(),
          unbounded = value.boundingBox == null,
          minZoom = value.minZoom,
          maxZoom = value.maxZoom,
          minPitch = value.minPitch,
          maxPitch = value.maxPitch,
        )
      )
      .report("set the camera constraints")
  }

  override fun setRenderSettings(value: RenderOptions) {
    if (maximumFps != value.maximumFps) {
      maximumFps = value.maximumFps
      presentation.requestRender()
    }

    val projectionChanged = cameraProjection != value.cameraProjection
    cameraProjection = value.cameraProjection
    renderSettings = value
    map?.applyRenderSettings(value, projectionChanged)
  }

  private fun MapHandle.applyRenderSettings(value: RenderOptions, projectionChanged: Boolean) {
    var options = MapDebugOption(0u)
    if (value.debug.tileBorders) options = options or MapDebugOption.TILE_BORDERS
    if (value.debug.tileTimestamps) options = options or MapDebugOption.TIMESTAMPS
    if (value.debug.collisionBoxes) options = options or MapDebugOption.COLLISION
    if (value.debug.tileParseStatus) options = options or MapDebugOption.PARSE_STATUS
    setDebugOptions(options).report("set the debug options")
    if (projectionChanged) {
      val mode = setProjectionMode(value.cameraProjection.toFfi()).report("set the projection mode")
      // The projection reshapes the viewport without a camera event.
      owner.launchOnMain {
        mode.await()
        owner.drain()
        if (this@MlnFfiMapSession.map !== this@applyRenderSettings || isClosing) return@launchOnMain
        viewport.refresh(this@applyRenderSettings)
        notifyViewportChanged()
      }
    }
  }

  override fun setTileLodSettings(value: TileLodOptions) {
    if (value == tileLodOptions) return

    tileLodOptions = value
    map?.setTileOptions(value.toFfi())?.report("set the tile options")
  }

  @OptIn(DelicateMaplibreComposeApi::class, ExperimentalMaplibreComposeApi::class)
  internal fun <T> withPlatformMap(block: PlatformMapScope.() -> T): T {
    check(!isClosing) { "The map state is closed" }
    val map = checkNotNull(map) { "The presented map does not exist" }
    val result = PlatformMapScope(map).block()
    owner.drain()
    if (!isClosing && this.map === map) {
      viewport.refresh(map)
      notifyViewportChanged()
    }
    return result
  }

  override suspend fun fitBoundsAwaitingTransition(
    fit: BoxZoomFit,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) {
    if (!acceptsGestures) return
    val map = map ?: return
    val camera = cameraForBounds(map, fit.bounds, fit.bearing, fit.pitch, null, DpPadding.Zero)
    startTransitionAwaitingRelease(duration.toAnimationOptions(), gestureToken = gestureToken) {
      target,
      animation ->
      target.updateCamera(
        FfiCameraUpdate(mode = CameraUpdateMode.EASE, camera = camera, animation = animation)
      )
    }
  }

  override suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> = query(offset.toQueryGeometry(), layerIds, predicate)

  override suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> = query(rect.toQueryGeometry(), layerIds, predicate)

  /** Submits every layer's query at once instead of one round trip per layer. */
  override suspend fun queryRenderedFeaturesByLayer(
    offset: DpOffset,
    hitPadding: Map<String, Dp>,
  ): Map<String, List<Feature<Geometry, JsonObject?>>> {
    val session = presentation.readySession() ?: return hitPadding.mapValues { emptyList() }
    val pending = hitPadding.mapValues { (id, padding) ->
      val geometry =
        if (padding == 0.dp) offset.toQueryGeometry()
        else
          DpRect(offset.x - padding, offset.y - padding, offset.x + padding, offset.y + padding)
            .toQueryGeometry()
      submitQuery(session, geometry, setOf(id), null)
    }
    val results = pending.mapValues { (_, query) -> query?.awaitQuery() ?: emptyList() }
    return withContext(Dispatchers.Default) {
      results.mapValues { (_, features) -> features.toGeoJsonFeatures().asReversed() }
    }
  }

  private fun DpOffset.toQueryGeometry(): RenderedQueryGeometry =
    RenderedQueryGeometry(RenderedQueryGeometryData.Point(toScreenPoint()))

  private fun DpRect.toQueryGeometry(): RenderedQueryGeometry =
    RenderedQueryGeometry(
      RenderedQueryGeometryData.Box(
        ScreenBox(
          min = DpOffset(left, top).toScreenPoint(),
          max = DpOffset(right, bottom).toScreenPoint(),
        )
      )
    )

  /** Rendered feature state belongs to the render session, so a query without one is empty. */
  private suspend fun query(
    geometry: RenderedQueryGeometry,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): List<Feature<Geometry, JsonObject?>> {
    val session = presentation.readySession() ?: return emptyList()
    val result =
      submitQuery(session, geometry, layerIds, predicate)?.awaitQuery() ?: return emptyList()
    return withContext(Dispatchers.Default) {
      // Native walks style layers from the bottom. MapState and GL JS put the feature in front
      // first.
      result.toGeoJsonFeatures().asReversed()
    }
  }

  /** Null when the session closed before it accepted the query. */
  private fun submitQuery(
    session: RenderSessionHandle,
    geometry: RenderedQueryGeometry,
    layerIds: Set<String>?,
    predicate: CompiledExpression<BooleanValue>?,
  ): Deferred<List<QueriedFeature>>? =
    try {
      session.queryRenderedFeatures(geometry, renderedQueryOptions(layerIds, predicate))
    } catch (error: MaplibreException) {
      if (error.status != MaplibreStatus.INVALID_STATE) throw error
      null
    }

  /** A session that detached before answering has nothing to report. */
  private suspend fun Deferred<List<QueriedFeature>>.awaitQuery(): List<QueriedFeature> =
    try {
      await()
    } catch (error: MaplibreException) {
      if (
        error.status != MaplibreStatus.INVALID_STATE && error.status != MaplibreStatus.TARGET_LOST
      )
        throw error
      emptyList()
    }

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

  override fun readySession(): RenderSessionHandle? = presentation.readySession()

  // endregion

  // region input, called from Compose

  override val isGestureReady: Boolean
    get() = canPresentFrames && owner.failure == null && viewport.hasViewport

  override fun observeInput(): Long = lifecycleAuthority.gestureCamera.observeInput()

  override val inputGeneration: Long
    get() = lifecycleAuthority.gestureCamera.generation

  override fun onGestureStartedIfCurrent(generation: Long): CameraInputToken? =
    lifecycleAuthority.gestureCamera.acquireIfCurrent(this, generation)

  override fun onGestureStarted(): CameraInputToken =
    lifecycleAuthority.gestureCamera.acquire(this).also { token ->
      // Recognition takes over an existing transition even before the first movement.
      submitCameraInput(token) { phase -> updateCamera(FfiCameraUpdate(gesturePhase = phase)) }
    }

  override fun onGestureEnded(token: CameraInputToken) = lifecycleAuthority.postToMain {
    finishGesture(token, cancelled = false)
  }

  override fun cancelGesture(token: CameraInputToken) = lifecycleAuthority.postToMain {
    finishGesture(token, cancelled = true)
  }

  private fun finishGesture(token: CameraInputToken, cancelled: Boolean) {
    token.finish(cancelled) {
      val map = map
      if (map == null) {
        token.complete()
        return@finish
      }
      if (activeGestureToken !== token) {
        token.complete()
        return@finish
      }
      activeGestureToken = null
      gestureBearing = null
      if (cancelled) map.cancelTransitions().report("cancel camera transitions")
      val last =
        map.updateCamera(FfiCameraUpdate(gesturePhase = GesturePhase.END)).report("end a gesture")
      gestureEnd = last
      owner.launchOnMain {
        try {
          gestureEndGeneration = last.await().generation
          owner.drain()
          if (activeGestureToken == null) reportGestureActive(false)
        } finally {
          if (gestureEnd === last) gestureEnd = null
          token.complete()
        }
      }
    }
  }

  /**
   * Makes [token] the gesture that owns the camera, and returns the phase its command carries.
   * Reports on every camera command, because a report made before presentation attachment is
   * dropped.
   */
  private fun activateGesture(map: MapHandle, token: CameraInputToken): GesturePhase {
    val phase =
      if (activeGestureToken !== token) {
        map.cancelTransitions().report("cancel camera transitions")
        activeGestureToken = token
        gestureBearing = null
        GesturePhase.BEGIN
      } else GesturePhase.UPDATE
    reportGestureActive(true)
    return phase
  }

  /**
   * Reports whether a gesture holds the camera. The start is reported before the gesture's first
   * command is submitted, so it precedes that command's camera events; the end waits for the
   * gesture's last command to drain.
   */
  private fun reportGestureActive(active: Boolean) {
    if (presentationActive) callbacks.onGestureActive(this, active)
  }

  /**
   * Before the presentation is visible the map has only its bootstrap viewport, and a camera
   * command projected through it jumps the camera. Gestures are dropped until then.
   */
  private val acceptsGestures: Boolean
    get() = canPresentFrames

  private fun submitCameraInput(
    gestureToken: CameraInputToken?,
    command: MapHandle.(GesturePhase) -> Deferred<CommandCompletion>?,
  ) {
    if (!acceptsGestures) return
    val map = map ?: return
    var phase = GesturePhase.NONE
    runCameraCommand(
      gestureToken,
      activate = { gestureToken?.let { phase = activateGesture(map, it) } },
    ) {
      map.command(phase)?.report("move the camera")
    }
  }

  override fun moveBy(deltaX: Double, deltaY: Double, gestureToken: CameraInputToken?) {
    submitCameraInput(gestureToken) { phase ->
      applyCameraDelta(CameraDelta(offset = ScreenPoint(deltaX, deltaY), gesturePhase = phase))
    }
  }

  override suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) {
    if (!acceptsGestures) return
    startTransitionAwaitingRelease(
      duration.toAnimationOptions(),
      gestureToken = gestureToken,
      fields = setOf(TransitionField.Center),
    ) { map, animation ->
      map.applyCameraDelta(CameraDelta(offset = ScreenPoint(deltaX, deltaY), animation = animation))
    }
  }

  override fun scaleBy(scale: Double, anchor: DpOffset?, gestureToken: CameraInputToken?) {
    submitCameraInput(gestureToken) { phase ->
      applyCameraDelta(
        CameraDelta(scale = scale, anchor = anchor?.toScreenPoint(), gesturePhase = phase)
      )
    }
  }

  override suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: CameraInputToken,
  ) {
    if (!acceptsGestures) return
    startTransitionAwaitingRelease(
      duration.toAnimationOptions(),
      gestureToken = gestureToken,
      fields = setOf(TransitionField.Center, TransitionField.Zoom),
    ) { map, animation ->
      map.applyCameraDelta(
        CameraDelta(scale = scale, anchor = anchor?.toScreenPoint(), animation = animation)
      )
    }
  }

  private fun Duration.toAnimationOptions() =
    AnimationOptions(durationMs = inWholeMilliseconds.toDouble())

  /**
   * One relative command, so a burst of rotations never reads a camera that its own earlier
   * rotations have not reached yet. Not the FFI's two-point rotation, which derives an angle
   * between two pointer positions and would rotate around the wrong centre here.
   */
  override fun rotateAndPitchBy(
    bearingDelta: Double,
    pitchDelta: Double,
    anchor: DpOffset?,
    gestureToken: CameraInputToken?,
    feedback: Boolean,
  ) {
    submitCameraInput(gestureToken) { phase ->
      if (feedback && bearingDelta != 0.0 && gestureToken != null) {
        val (from, to) =
          run {
            val from = gestureBearing ?: getSnapshot().camera.bearing ?: 0.0
            val to = from + bearingDelta
            gestureBearing = to
            from to to
          }
        gestureToken.reportRotation(from, to)
      }
      val bearing = bearingDelta.takeIf { it != 0.0 }
      val pitch = pitchDelta.takeIf { it != 0.0 }
      if (bearing == null && pitch == null) {
        // Nothing moves, but a gesture boundary still reaches the map.
        if (phase == GesturePhase.NONE) null
        else updateCamera(FfiCameraUpdate(gesturePhase = phase))
      } else {
        applyCameraDelta(
          CameraDelta(
            bearing = bearing,
            pitch = pitch,
            anchor = anchor?.toScreenPoint(),
            gesturePhase = phase,
          )
        )
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
      fields = setOf(TransitionField.Bearing),
      shouldStart = { map ->
        val current = map.getSnapshot().camera.bearing ?: 0.0
        snapping.delta(current)?.let {
          bearing = current + it
          true
        } ?: false
      },
    ) { map, animation ->
      map.updateCamera(
        FfiCameraUpdate(
          mode = CameraUpdateMode.EASE,
          camera = CameraOptions(bearing = bearing),
          animation = animation,
        )
      )
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
    startTransitionAwaitingRelease(
      duration.toAnimationOptions(),
      gestureToken = gestureToken,
      fields = setOf(TransitionField.Center, TransitionField.Bearing, TransitionField.Pitch),
    ) { map, animation ->
      val camera = map.getSnapshot().camera
      map.updateCamera(
        FfiCameraUpdate(
          mode = CameraUpdateMode.EASE,
          camera =
            CameraOptions(
              anchor = anchor?.toScreenPoint(),
              bearing = (camera.bearing ?: 0.0) + bearingDelta,
              pitch =
                ((camera.pitch ?: 0.0) + pitchDelta).coerceIn(MinPitchDegrees, MaxPitchDegrees),
            ),
          animation = animation,
        )
      )
    }
  }

  override fun interruptCamera() {
    stopCameraMovement(lifecycleAuthority.gestureCamera.beginProgrammatic())
  }

  override fun stopCameraMovement(guard: CameraCommandGuard) {
    if (!guard.isValid()) return
    map?.cancelTransitions()?.report("cancel camera transitions")
  }

  private fun <T> Deferred<T>.report(what: String): Deferred<T> = reportFailure(logger, what)

  // endregion
}

private typealias TransitionField = MlnFfiCameraTransitions.Field

/** The camera fields an absolute update animates. */
private fun CameraUpdate.fields(): Set<TransitionField> = buildSet {
  if (center != null) add(TransitionField.Center)
  if (zoom != null) add(TransitionField.Zoom)
  if (bearing != null) add(TransitionField.Bearing)
  if (pitch != null) add(TransitionField.Pitch)
  if (padding != null) add(TransitionField.Padding)
}

private fun TileLodOptions.toFfi(): MapTileOptions = algorithm.toFfi()

@OptIn(ExperimentalMaplibreComposeApi::class)
private fun CameraProjection.toFfi(): ProjectionMode =
  when (this) {
    CameraProjection.Perspective -> ProjectionMode(axonometric = false)
    is CameraProjection.Axonometric ->
      ProjectionMode(axonometric = true, xSkew = xSkew, ySkew = ySkew)
  }
