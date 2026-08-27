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
import co.touchlab.kermit.Logger
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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.resource.MlnFfiResourceProviderFactory
import org.maplibre.compose.sources.MlnFfiFeatureStateStore
import org.maplibre.compose.sources.MlnFfiTileCoordinatorStore
import org.maplibre.compose.style.BaseStyle
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
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.geo.ScreenBox
import org.maplibre.nativeffi.geo.ScreenPoint
import org.maplibre.nativeffi.map.DebugOption
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapProjectionHandle
import org.maplibre.nativeffi.map.TileLodMode as FfiTileLodMode
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

private const val MIN_PITCH_DEGREES = 0.0

/** MapLibre rejects a pitch beyond this, so the drag is clamped rather than throwing. */
private const val MAX_PITCH_DEGREES = 60.0

/** The events [MlnFfiMapCore.handleEvent] consumes. */
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

/**
 * What [MlnFfiMapCore] asks of an attached [MlnFfiMapSession]. Every member tolerates a session
 * whose renderer is not ready yet.
 */
internal interface MlnFfiRenderSessionAccess {
  /** Schedules a frame; safe from any thread. */
  fun requestRender()

  /** Runs [action] against the live render session on its thread, or null when none is ready. */
  fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T?

  /**
   * Queues [work] onto the renderer thread with the live render session or null; false when the
   * host cannot run it.
   */
  fun enqueueRenderSessionWork(work: (RenderSessionHandle?) -> Unit): Boolean

  /** Closes the render session so the map underneath it can be destroyed. */
  fun closeRenderSession()
}

/**
 * The runtime and the map, on [MlnFfiMapRuntimeLoop]'s thread: style, camera, sources, and the
 * mirrored viewport any thread may read. Rendering belongs to the [MlnFfiMapSession] that attaches
 * through [attachRenderSession]; without one, render-session queries answer as they do before the
 * first frame.
 */
internal class MlnFfiMapCore(
  @Volatile internal var callbacks: MapAdapter.Callbacks,
  @Volatile internal var logger: Logger?,
  scaleFactor: Double = 1.0,
  @Volatile internal var layoutDirection: LayoutDirection,
  // Supplied only by the tests that build a core against their own cache and resources.
  private val cacheFile: Path = MlnFfiApplication.options.cacheFile,
  private val resourceProviderFactory: MlnFfiResourceProviderFactory =
    MlnFfiApplication.options.resourceProviderFactory,
) : MapAdapter, GestureTarget, AutoCloseable {

  private val initialExtent = MapExtent.fromLogical(1, 1, scaleFactor)

  /** Guards loop startup and actions accepted before it. */
  private val stateLock = MlnFfiLock()

  @Volatile private var loop: MlnFfiMapRuntimeLoop? = null

  /** The loop while it runs, read by the render session on its own thread. */
  internal val runtimeLoop: MlnFfiMapRuntimeLoop?
    get() = loop

  @Volatile private var cameraPadding: EdgeInsets = EdgeInsets.ZERO

  /** One-shot map actions accepted before this core starts. Guarded by [stateLock]. */
  private class PendingMapAction(val run: (MapHandle) -> Unit, val abandon: () -> Unit)

  private val pendingMapActions = mutableListOf<PendingMapAction>()

  /** Bounds fits accepted before the first real render target. Guarded by [stateLock]. */
  private val pendingViewportActions = mutableListOf<PendingMapAction>()

  /** Guarded by [stateLock]; once true, the map has dimensions suitable for fitting bounds. */
  private var hasAttachedViewport = false

  /** Any thread may publish style or source state that the next rendered update must receive. */
  private val featureStateReplayPending = AtomicBoolean(false)

  @Volatile private var renderAccess: MlnFfiRenderSessionAccess? = null

  /**
   * True once this map has loaded a style. It stays true so a later style switch does not put the
   * load placeholder back over a live map.
   */
  internal var hasLoadedFirstStyle by mutableStateOf(false)
    private set

  @Volatile private var closed = false

  /** True after [close]; tests read it to observe eviction ordering. */
  internal val isClosed: Boolean
    get() = closed

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

  @Volatile internal var maximumFps: Int? = null
  private var tileLodOptions: TileLodOptions = TileLodOptions.Standard

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
        callbacks.onSourceChanged(this, id)
      }
    }
  }

  /** Once [unload] runs, writes are dropped rather than reaching a map whose style was replaced. */
  private inner class SessionStyleBinding : MlnFfiStyleBinding {
    @Volatile private var loaded = true
    private val unloadActions = mutableSetOf<() -> Unit>()
    private val unloadActionsLock = MlnFfiLock()

    override val featureStateStore = MlnFfiFeatureStateStore()

    override val tileCoordinators = MlnFfiTileCoordinatorStore()

    override val isLoaded: Boolean
      get() = loaded && !closed

    override val logger: Logger?
      get() = this@MlnFfiMapCore.logger

    override val imageScale: Float
      get() = this@MlnFfiMapCore.imageScale()

    fun unload() {
      loaded = false
      val actions = unloadActionsLock.withLock {
        unloadActions.toList().also { unloadActions.clear() }
      }
      actions.forEach { it() }
    }

    override fun onUnload(action: () -> Unit): () -> Unit {
      if (!isLoaded) {
        action()
        return {}
      }
      var runImmediately = false
      unloadActionsLock.withLock {
        if (!isLoaded) {
          runImmediately = true
        } else {
          unloadActions += action
        }
      }
      if (runImmediately) {
        action()
        return {}
      }
      return { unloadActionsLock.withLock { unloadActions -= action } }
    }

    override fun reportSourceChanged(sourceId: String) {
      featureStateReplayPending.store(true)
      reportedUrlAttribution.remove(sourceId)
      callbacks.onSourceChanged(this@MlnFfiMapCore, sourceId)
    }

    /** A style load can unload this binding between a caller's check and its queued closure. */
    private val isCurrent: Boolean
      get() = isLoaded && styleBinding === this

    override fun <T> readMap(action: (MapHandle) -> T): T? {
      if (!isCurrent) return null
      return runOnMap<T?> { map -> if (isCurrent) action(map) else null }
    }

    /**
     * `addSource`, `removeSource` and `removeImage` notify mbgl of nothing, so they render stale.
     */
    override fun <T> mutateMap(abandon: () -> Unit, action: (MapHandle) -> T): T? {
      if (!isCurrent) {
        abandon()
        return null
      }
      return runOnMap<T?>(abandon) { map ->
        if (!isCurrent) {
          abandon()
          null
        } else {
          action(map).also { map.requestRepaint() }
        }
      }
    }

    override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? {
      if (!isCurrent) return null
      return renderAccess?.withRenderSession<T?> { session ->
        if (isCurrent) action(session) else null
      }
    }
  }

  // region render session attachment

  /** Called by the session that will draw this map; one at a time. */
  internal fun attachRenderSession(access: MlnFfiRenderSessionAccess) {
    renderAccess = access
  }

  /** Only [access] detaches itself, so a replacement attach is never undone by the old session. */
  internal fun detachRenderSession(access: MlnFfiRenderSessionAccess) {
    if (renderAccess === access) renderAccess = null
  }

  /** Renderer thread, on each new render target: the pending replay must reach the new session. */
  internal fun markFeatureStateReplayPending() {
    featureStateReplayPending.store(true)
  }

  /** Renderer thread, after a rendered frame: replays feature state the frame may have missed. */
  internal fun replayPendingFeatureState(session: RenderSessionHandle) {
    if (featureStateReplayPending.compareAndSet(true, false)) {
      if (styleBinding?.featureStateStore?.replay(session) == true) requestRender()
    }
  }

  /**
   * A resize changes the projection without a camera event, so Compose overlays that key on
   * [org.maplibre.compose.map.MapState.viewport] would keep the previous screen locations unless
   * this reports the new snapshot.
   */
  internal fun postViewportSnapshot() {
    onMap(::snapshotViewportAndNotify)
  }

  // endregion

  // region lifecycle

  override fun close() {
    if (closed) return
    closed = true
    try {
      // The onUnload actions must run before the map they clean up after is destroyed.
      styleBinding?.unload()
      styleBinding = null
      stopLoop(endOutstandingMove = true)
    } finally {
      // The owner thread is gone, so this is the last published handle. Destruction is any-thread.
      retireProjection()
    }
  }

  fun start() {
    val started = stateLock.withLock {
      check(!closed) { "Cannot start a closed map core" }
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
    renderAccess?.closeRenderSession()
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

  /** Runs on the loop's thread, once, before the map is published. */
  private fun onMapCreated(map: MapHandle) {
    applyRequestedStyle(map)
    // A camera set before this map existed reaches it as a queued jump, which a loop that stopped
    // before running it has already abandoned.
    requestedCamera?.let { map.jumpTo(it.toCameraOptions(cameraPadding)) }
  }

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
        featureStateReplayPending.store(true)
        hasLoadedFirstStyle = true
        callbacks.onStyleChanged(this, binding)
        styleLoadUnreported = true
        reportedUrlAttribution.clear()
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
          reportNewlyArrivedAttribution()
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
      // seen. Types this core does not select are never queued.
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

  private fun imageScale(): Float = (loop?.scaleFactor ?: 1.0).toFloat()

  /** Safe from any thread; a request with no attached render session is dropped. */
  private fun requestRender() {
    renderAccess?.requestRender()
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

  /** Queues [action] until a map exists, including before the core starts. */
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

  /** Renderer thread, when the first real render target attaches its dimensions. */
  internal fun publishAttachedViewport() {
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

  private fun snapshotViewportAndNotify(map: MapHandle) {
    snapshotViewport(map)
    callbacks.onCameraMoved(this)
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
      snapshotViewport(map)
    }
  }

  override fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    padding: PaddingValues,
  ) {
    val fit: (MapHandle) -> Unit = { map ->
      map.jumpTo(cameraForBounds(map, boundingBox, bearing, tilt, padding))
      snapshotViewport(map)
    }
    // The fit reads the live map's dimensions, so before a viewport exists it can only queue.
    // After one exists it runs as one round-trip instead, so a camera or viewport read made right
    // after this call observes the fitted camera rather than the previous mirrored snapshot —
    // the ordering the blocking getters on main provided.
    val hasViewport = stateLock.withLock { hasAttachedViewport && !closed && loop != null }
    if (hasViewport && runOnMap(fit) != null) return
    postWhenViewportExists(fit, abandon = {})
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

  private fun getVisibleBoundingBox(): BoundingBox = mirroredViewport.boundingBox

  private fun getVisibleRegion(): VisibleRegion = mirroredViewport.visibleRegion

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

  override fun setTileLodSettings(value: TileLodOptions) {
    if (value == tileLodOptions) return
    tileLodOptions = value
    configureMap { map -> map.tileOptions = value.toFfi() }
  }

  /** Whether the loaded map reports itself fully loaded; false while no map exists. */
  internal fun isMapFullyLoaded(): Boolean = runOnMap { it.isFullyLoaded } == true

  /**
   * Runs [action] on the map's owner thread and returns its result, waiting for the map when it
   * does not exist yet. Fails with [IllegalStateException] when this core closes first. The public
   * [withPlatformMap] hop.
   */
  internal suspend fun <T> withMapHandle(action: (MapHandle) -> T): T =
    suspendCancellableCoroutine { continuation ->
      val accepted =
        postWhenMapExists(
          action = { map -> continuation.resumeWith(runCatching { action(map) }) },
          abandon = {
            if (continuation.isActive) {
              continuation.resumeWithException(
                IllegalStateException("MapState was closed before the platform map call ran")
              )
            }
          },
        )
      if (!accepted && continuation.isActive) {
        continuation.resumeWithException(
          IllegalStateException("MapState is closed; the platform map is destroyed")
        )
      }
    }

  /** Dirties the map so the next render update redraws, for a snapshot's final frame. */
  internal fun postSnapshotRepaint() {
    onMap { it.requestRepaint() }
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
    if (closed) {
      continuation.resume(emptyList())
      return@suspendCancellableCoroutine
    }
    val access = renderAccess
    if (access == null) {
      continuation.resume(emptyList())
      return@suspendCancellableCoroutine
    }
    val accepted = access.enqueueRenderSessionWork { session ->
      if (!continuation.isActive) return@enqueueRenderSessionWork
      if (session == null) {
        continuation.resume(emptyList())
        return@enqueueRenderSessionWork
      }
      continuation.resumeWith(
        runCatching {
          session
            .queryRenderedFeatures(geometry, renderedQueryOptions(layerIds, predicate))
            .toGeoJsonFeatures()
            // Native walks style layers from the bottom. CameraState and GL JS put
            // the feature in front first.
            .asReversed()
        }
      )
    }
    if (!accepted && continuation.isActive) continuation.resume(emptyList())
  }

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
    val position = withSnapshotProjection { it.latLngForPixel(offset.toScreenPoint()).toPosition() }
    if (position == null) {
      logger?.w { "Dropped a map click at $offset: the map has no viewport" }
      return
    }
    callbacks.onClick(this, position, offset)
  }

  /** A mouse has no press-and-hold convention, so the secondary button is the long press. */
  override fun onSecondaryClick(offset: DpOffset) {
    if (closed) return
    val position = withSnapshotProjection { it.latLngForPixel(offset.toScreenPoint()).toPosition() }
    if (position == null) {
      logger?.w { "Dropped a map long click at $offset: the map has no viewport" }
      return
    }
    callbacks.onLongClick(this, position, offset)
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
