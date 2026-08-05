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
import org.maplibre.compose.desktop.DesktopFrameResult
import org.maplibre.compose.desktop.DesktopMapExtent
import org.maplibre.compose.desktop.DesktopMapFatalFrameException
import org.maplibre.compose.desktop.DesktopMapFrame
import org.maplibre.compose.desktop.DesktopMapHostSession
import org.maplibre.compose.desktop.DesktopMapRenderer
import org.maplibre.compose.desktop.DesktopRenderTarget
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.desktop.EglContextHandles
import org.maplibre.compose.desktop.MapRenderBackend
import org.maplibre.compose.desktop.MetalTextureTarget
import org.maplibre.compose.desktop.OpenGlTextureTarget
import org.maplibre.compose.desktop.VulkanImageTarget
import org.maplibre.compose.desktop.WglContextHandles
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesktopStyle
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

/** Degrees of bearing per logical pixel of horizontal drag. */
private const val DRAG_ROTATE_DEGREES_PER_PIXEL = 0.5

/** Degrees of pitch per logical pixel of vertical drag. */
private const val DRAG_PITCH_DEGREES_PER_PIXEL = 0.5

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

/**
 * The fraction of a capped frame interval a frame may arrive early and still be drawn.
 *
 * See [DesktopMapSession.allowRenderNow], which is the only thing that reads it.
 */
private const val FRAME_INTERVAL_SLACK = 0.1

/**
 * Configuration key for the camera.
 *
 * Named because two setters write it — a position and a bounds fit — and the later of the two must
 * replace the earlier rather than both replaying onto a new map.
 */
private const val CAMERA_KEY = "camera"

/**
 * Drives one MapLibre Native map on a desktop host surface.
 *
 * Two threads, deliberately. The runtime and the map belong to [DesktopMapRuntimeLoop], which parks
 * in MapLibre's own pump, so style parsing, tile loads, and resource responses advance whether or
 * not anything is drawing. The render session belongs to the host's renderer thread, which is where
 * [render] runs: since maplibre-native-ffi #399 a session's owner is whichever thread attached it,
 * so it does not have to be the map's.
 *
 * Camera transitions are the exception to "advances on its own": mbgl steps one from
 * `onDidFinishRenderingFrame` while `transform.inTransition()`, so they still need frames. The
 * cycle is self-sustaining once it starts, because a transition in progress publishes the next
 * render update, which asks for the next frame.
 *
 * This class is the seam between them, and the [MapAdapter] Compose talks to. Everything that
 * touches the map hops to the loop — blocking when the caller needs an answer, posting when it does
 * not — and everything that touches the render session stays on the renderer thread.
 */
internal class DesktopMapSession(
  internal var callbacks: MapAdapter.Callbacks,
  internal var logger: Logger?,
  renderBackend: MapRenderBackend,
  private val layoutDirection: LayoutDirection,
  private val runtimeOptions: DesktopRuntimeOptions,
) : MapAdapter, DesktopMapRenderer {

  override val backend: MapRenderBackend = renderBackend

  /**
   * Guards [loop] and [mapConfiguration] together, so a setting cannot be lost to a racing start.
   */
  private val stateLock = ReentrantLock()

  @Volatile private var loop: DesktopMapRuntimeLoop? = null

  /** Renderer-thread state. The session is this handle's owner, so nothing else may touch it. */
  private var renderSession: RenderSessionHandle? = null

  @Volatile private var hostSession: DesktopMapHostSession? = null

  /** Identifies the target a render session is attached to; a change forces a re-attach. */
  private data class TargetKey(val generation: Long, val extent: DesktopMapExtent)

  private var attachedTarget: TargetKey? = null

  /**
   * How many render sessions this session has attached, and how many targets it handed to a live
   * one instead.
   *
   * Renderer-thread state, read by tests. The difference between the two is the whole point of
   * following a new target in place — an attach rebuilds the renderer and refetches every tile,
   * where a retarget keeps both — and it is not observable from the outside in any other way, since
   * both paths end with the map rendering the same scene into the same size.
   */
  @Volatile
  internal var attachCount: Int = 0
    private set

  @Volatile
  internal var retargetCount: Int = 0
    private set

  /**
   * Whether a frame is worth drawing.
   *
   * Set by the map's owner thread when MapLibre publishes an update, consumed by the renderer
   * thread before it renders — before rather than after, so a request published during a render is
   * not discarded.
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
   *
   * Owner-thread state: every read and write is from an event handler or from work posted to that
   * thread, which is what lets it be a plain field. See [beginCameraMove].
   */
  private var reportedMoveReason: CameraMoveReason? = null

  /**
   * The map's configuration, keyed so the last value for each setting wins.
   *
   * Everything a new map has to be told to match the old one: the camera, its limits, and the debug
   * overlays. Replayed in insertion order onto every map this session creates. See [configureMap].
   */
  private val mapConfiguration = LinkedHashMap<String, (MapHandle) -> Unit>()

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

    override val logger: Logger?
      get() = this@DesktopMapSession.logger

    fun unload() {
      loaded = false
    }

    /**
     * Runs [action] against the map on its owner thread.
     *
     * The repaint request is the part that is not obvious. Most mutations make mbgl notify us on
     * its own, and the loop drains that notification on its next pump. `addSource`, `removeSource`,
     * and `removeImage` notify nothing at all, so without this they would render stale. Every
     * source, layer, and image write crosses this one seam, so this is where the request belongs
     * rather than at each of the dozen call sites, where the next one added would silently forget
     * it.
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
        // Deliberately uncaught. The one routine reason a call cannot proceed — no session yet — is
        // the check above; anything MapLibre throws past that point is a wrong thread, a handle
        // used after close, or bad input, all of which are bugs here. Swallowing them would turn a
        // broken query into one that silently answers nothing, which is exactly what made the
        // cluster id typing bug so hard to find.
        action(session)
      }
    }
  }

  private var maximumFps: Int? = null
  private var lastRenderTime = TimeSource.Monotonic.markNow()

  private val frameTimer = TimeSource.Monotonic
  private var lastFrameTime = frameTimer.markNow()

  // ───────────────────────────── host surface lifecycle ─────────────────────────────

  override fun onSurfaceAvailable(session: DesktopMapHostSession) {
    // A closed session has no map to draw and no thread to draw it on, so taking the surface would
    // only leave a reference to a host that is about to be freed.
    if (closed) {
      logger?.w { "Ignoring a host surface offered to a closed map session" }
      return
    }
    hostSession = session
    // Asked for here, and this is load-bearing on the second surface rather than the first.
    //
    // Frames are requested rather than continuous, and the only thing that requests one is the
    // owner thread publishing a render update. A surface that returns after loss gets no such
    // event: the map is idle, its camera is not moving, and MapLibre has nothing new to say — it
    // is holding the same update it already produced. So without this the new surface is never
    // drawn into and the map stays blank with both threads parked and nothing logged, which is
    // exactly the symptom a sleep/wake cycle produced. MapLibre re-renders its retained update on
    // demand, so one frame is all this needs to buy.
    requestRender()
  }

  override fun onSurfaceChanged(extent: DesktopMapExtent) {
    // The map is resized as part of attaching the new target; see ensureAttached.
    requestRender()
  }

  override fun onSurfaceLost() {
    // The host is about to free the target these handles point at, so the render session must go
    // first. The map and runtime outlive surface loss and are reused if a surface returns.
    //
    // Logged at info because this is the last thing that happens before a map goes quiet: if a
    // surface never comes back, this line is the difference between a diagnosable report and "it
    // stopped rendering after the machine slept".
    logger?.i { "Host surface lost; closing the render session and waiting for a new one" }
    // Before the host session is dropped, because that is the only route to the thread allowed to
    // close the handle.
    closeRenderSession()
    hostSession = null
  }

  override fun render(frame: DesktopMapFrame): DesktopFrameResult {
    if (closed || frame.extent.isEmpty) return DesktopFrameResult.SKIPPED

    val loop = ensureLoop(frame.extent)
    loop.failure?.let { error ->
      if (!failureReported) {
        failureReported = true
        // Closed here for the same reason the example does: the host stops driving frames after a
        // failure, so nothing else would close the render session, and the loop cannot destroy a
        // map that still has one attached.
        close()
        // Marked fatal so the surface latches instead of rebuilding its render session and trying
        // again. The runtime this map needs is gone, and a fresh surface cannot bring it back —
        // retrying would only replace a reported failure with a blank map, because every frame
        // after the close returns SKIPPED without throwing anything for the surface to notice.
        throw DesktopMapFatalFrameException("The MapLibre map runtime failed", error)
      }
      return DesktopFrameResult.SKIPPED
    }

    // Null until the loop has created its map. It asks for a frame when it has, so there is
    // nothing to schedule here.
    val map = loop.map ?: return DesktopFrameResult.SKIPPED

    if (!ensureAttached(map, frame)) return DesktopFrameResult.SKIPPED
    // Consumed before rendering, so an update the loop publishes during the render below is not
    // discarded along with the one being drawn.
    if (!renderRequested.getAndSet(false)) return DesktopFrameResult.SKIPPED
    // Taken before the render and kept, so the interval the cap measures runs from one frame's
    // start to the next's. Measuring from the end of the last render instead makes every interval
    // short by however long that render took, which at a cap near the display's own rate is enough
    // to reject every second frame.
    val renderStart = TimeSource.Monotonic.markNow()
    if (!allowRenderNow(renderStart)) {
      // Throttled rather than dropped: ask for another frame so the update is not lost.
      requestRender()
      return DesktopFrameResult.SKIPPED
    }

    val session = renderSession ?: return DesktopFrameResult.SKIPPED
    // A false return means MapLibre had nothing to draw, which is ordinary before the style
    // produces its first update, and after an attach until the loop pumps the new size. Anything
    // thrown past here is a genuine failure — a closed session, a wrong thread — and propagates.
    if (!session.renderUpdate()) {
      requestRender()
      return DesktopFrameResult.SKIPPED
    }

    if (!hasRenderedAFrame) {
      hasRenderedAFrame = true
      // A blank desktop map is the failure mode with the least to go on, so record the moment
      // the first frame actually reaches the GPU. Its absence is the single most useful signal.
      // The extent is included because a blurry map almost always means the scale factor is
      // wrong, and this is the one place both the logical and physical size are known.
      logger?.i {
        "Rendered the first map frame with $backend on ${Thread.currentThread().name}, " +
          "extent ${frame.extent}"
      }
    }
    lastRenderTime = renderStart
    reportFrameRate()
    return DesktopFrameResult.RENDERED
  }

  override fun close() {
    if (closed) return
    closed = true
    try {
      stopLoop()
    } finally {
      hostSession = null
    }
  }

  /**
   * Closes the render session and then the loop that owns the map.
   *
   * The order is mandatory and enforced natively: MapLibre refuses to destroy a map that still has
   * a session attached, and the session can only be closed by the thread that attached it. So the
   * session goes first, on the renderer thread, and only then is the loop joined.
   */
  private fun stopLoop() {
    val stopping = stateLock.withLock { loop.also { loop = null } }
    closeRenderSession()
    stopping?.close()
    // After the join, so the owner thread is gone and this is the only reader of that state.
    resumeStrandedTransitions()
  }

  /**
   * Drops the render session, closing it if there is still a thread allowed to.
   *
   * The bookkeeping is cleared first and unconditionally, which is the part that matters after a
   * lost device: making the host's context current can itself throw then, and a version of this
   * that only forgot the handle once the close succeeded would leave [attachedTarget] naming a
   * target that no longer exists. The next frame would either hand a dead session a replacement
   * texture or attach a second session to a map that natively permits one — the first silently
   * renders nothing, the second throws. Neither is recoverable, and both look like the map simply
   * stopped.
   *
   * Never throws, so callers on a teardown or recovery path do not have to guard it.
   */
  private fun closeRenderSession() {
    val handle = renderSession
    renderSession = null
    attachedTarget = null
    if (handle == null) return

    val host = hostSession
    if (host == null) {
      // Only reachable if the surface went away without saying so. The handle can only be closed
      // by the thread that attached it, which is reached through the host, so all that is left is
      // to say that it leaked rather than to close it from the wrong thread and crash.
      logger?.w { "Leaking a MapLibre render session: its host surface is already gone" }
      return
    }
    runCatching { host.withRendererAccess { handle.close() } }
      .onFailure { logger?.e(it) { "Failed to close the MapLibre render session" } }
  }

  // ───────────────────────────── the map's owner thread ─────────────────────────────

  /**
   * Returns the loop for [extent], starting one or replacing it as needed.
   *
   * Called on the renderer thread, from [render], because that is where the first non-empty extent
   * is known and where the render session that must be closed before a replacement lives.
   */
  private fun ensureLoop(extent: DesktopMapExtent): DesktopMapRuntimeLoop {
    val existing = loop
    if (existing != null && existing.scaleFactor == extent.scaleFactor) return existing

    if (existing != null) {
      // pixelRatio is fixed at creation — mbgl holds it const — so a density change cannot be
      // applied by resizing or re-attaching; the map has to be rebuilt, or sprite and raster asset
      // density stay at the old scale. Tile selection is unaffected, only density. Rebuilding the
      // loop rather than the map alone keeps one map per runtime per thread.
      logger?.i {
        "Display scale changed from ${existing.scaleFactor} to ${extent.scaleFactor}; " +
          "recreating the map"
      }
      // Where the camera is now, not where it was last asked to be: the user has probably panned
      // since. Read before the loop stops, because afterwards there is nothing to read it from.
      existing.call { it.camera.toCameraPosition() }?.let(::recordCamera)
      stopLoop()
      // The new map starts styleless, so the style has to be applied again rather than skipped as
      // already applied.
      appliedStyle = null
    }

    val started =
      DesktopMapRuntimeLoop(
        extent = extent,
        runtimeOptions = runtimeOptions,
        logger = logger,
        onMapCreated = ::onMapCreated,
        onEvent = ::handleEvent,
        onEventsDrained = ::flushTransitionResumes,
        requestFrame = ::requestRender,
      )
    stateLock.withLock {
      loop = started
      // Snapshotted under the same lock that guards the recording, so a setting written while the
      // loop is starting is either in this snapshot or posted to the started loop, never lost.
      pendingConfiguration = mapConfiguration.values.toList()
    }
    started.start()
    return started
  }

  /** The configuration snapshot handed to a starting loop. Read on its thread, once. */
  @Volatile private var pendingConfiguration: List<(MapHandle) -> Unit> = emptyList()

  /** Runs on the loop's thread, once, before the map is published. */
  private fun onMapCreated(map: MapHandle) {
    // Replayed in order, so the map opens configured as the caller asked rather than at MapLibre's
    // defaults — on the first map because the composable configures it before any extent exists,
    // and on a replacement because a new map remembers nothing about the one it replaces.
    val pending = pendingConfiguration
    pendingConfiguration = emptyList()
    pending.forEach { setup ->
      runCatching { setup(map) }.onFailure { logger?.e(it) { "Failed to apply map configuration" } }
    }
    applyRequestedStyle(map)
  }

  /** Attaches or re-attaches the render session, returning whether one is usable. */
  private fun ensureAttached(map: MapHandle, frame: DesktopMapFrame): Boolean {
    val extent = frame.extent
    if (extent.isEmpty) return false

    val key = TargetKey(frame.target.generation, extent)
    val attached = attachedTarget
    if (attached == key && renderSession != null) return true

    // Follow the host's new target in place where that is possible. A renderer compiles its shaders
    // for one pixel ratio, so a scale-factor change starts a new renderer no matter which path is
    // taken — and it also needs a new map, because MapHandle's pixelRatio is fixed at creation, so
    // that case is already handled by the loop being replaced rather than here.
    val live = renderSession
    if (live != null && attached != null && attached.extent.scaleFactor == extent.scaleFactor) {
      if (retargetBorrowedTexture(live, frame.target, extent)) {
        attachedTarget = key
        retargetCount++
        // The replacement texture holds nothing yet, and MapLibre keeps its latest update, so the
        // frame this request buys is the one that fills it.
        renderRequested.set(true)
        return true
      }
    }

    // Nothing live to retarget, or it refused. Attaching before closing throws, because a map
    // permits only one live session.
    closeRenderSession()

    // No map.resize exists, and none should: the map's size is the size of what it renders into,
    // so attaching sets it from the descriptor's logical extent. A separate setter would only
    // create a way to leave the two disagreeing, which renders one viewport into another's
    // texture. The map publishes the result through MapHandle.size, applied on its owner thread
    // at the next pump.
    renderSession =
      try {
        attachBorrowedTexture(map, frame.target, extent)
      } catch (error: Throwable) {
        logger?.e(error) { "Failed to attach a render session to the host target" }
        throw error
      }
    attachedTarget = key
    attachCount++
    // The new texture holds nothing yet, and MapLibre keeps its latest update, so the frame this
    // request buys is the one that fills it.
    renderRequested.set(true)
    return true
  }

  private fun attachBorrowedTexture(
    map: MapHandle,
    target: DesktopRenderTarget,
    extent: DesktopMapExtent,
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
   * Since maplibre-native-ffi #485 a borrowed-texture session can be given a replacement texture
   * instead of being closed and re-attached, and it keeps the tile pyramid, the glyph and image
   * atlases, symbol placement, and renderer-held feature state across the change. That is the whole
   * reason to prefer this: a window resize used to throw all of it away and refetch.
   *
   * A replacement on another device or in another pixel format is refused, and refusal leaves the
   * session rendering into the texture it already has — so this reports false and the caller falls
   * back to closing and attaching, which is correct rather than fatal.
   */
  private fun retargetBorrowedTexture(
    session: RenderSessionHandle,
    target: DesktopRenderTarget,
    extent: DesktopMapExtent,
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
      // A replacement belonging to another device. Documented as leaving this session rendering
      // into the texture it has, so re-attaching is a recovery rather than a repair.
      return refusedTarget(error)
    } catch (error: UnsupportedFeatureException) {
      // A replacement in another pixel format, for the same reason.
      return refusedTarget(error)
    }
    return true
  }

  /**
   * Narrower than `catch (MaplibreException)` deliberately.
   *
   * These two are the refusals the FFI documents for a replacement target, and they are the only
   * ones a re-attach can plausibly fix. Its siblings are not: `WrongThreadException` means this ran
   * somewhere it must never run, and `InvalidStateException` means the session is already closed —
   * both are bugs here, and both would be swallowed into a silent re-attach that appears to work.
   */
  private fun refusedTarget(error: MaplibreException): Boolean {
    logger?.d(error) {
      "The render session would not take the host's replacement target; re-attaching instead"
    }
    return false
  }

  /**
   * The physical size a borrowed-texture descriptor states alongside its logical extent.
   *
   * `RenderTargetExtent` is logical, and a borrowed texture is sized by its owner, so the
   * descriptor states the physical size rather than deriving it. MapLibre rejects a pair that does
   * not agree, which is what makes DesktopMapExtent's single rounding rule load-bearing.
   */
  private fun DesktopMapExtent.toFfiExtent() =
    RenderTargetExtent(
      width = width.coerceAtLeast(1),
      height = height.coerceAtLeast(1),
      scaleFactor = scaleFactor,
    )

  private fun VulkanImageTarget.toDescriptor(extent: DesktopMapExtent) =
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

  private fun MetalTextureTarget.toDescriptor(extent: DesktopMapExtent) =
    MetalBorrowedTextureDescriptor(
      extent = extent.toFfiExtent(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1),
      texture = NativePointer.ofAddress(texture.address),
    )

  private fun OpenGlTextureTarget.toDescriptor(extent: DesktopMapExtent) =
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

  // ───────────────────────────── events, on the map's owner thread ─────────────────────────────

  /**
   * Translates one runtime event.
   *
   * Runs on the map's owner thread, which is also where the callbacks below are invoked from — the
   * same arrangement as before, when that thread happened to be the renderer's.
   */
  private fun handleEvent(event: RuntimeEvent) {
    when (event.type) {
      RuntimeEventType.MAP_RENDER_UPDATE_AVAILABLE -> requestRender()

      RuntimeEventType.MAP_RENDER_FRAME_FINISHED -> {
        val payload = event.payload
        if (payload is RuntimeEventPayload.RenderFrame && payload.needsRepaint) requestRender()
      }

      RuntimeEventType.MAP_STYLE_LOADED -> {
        // A new style replaces every source and layer, so the previous binding is dead. Marking
        // it unloaded is what makes descriptors that outlive it degrade rather than write into a
        // style that no longer exists.
        styleBinding?.unload()
        val binding = SessionStyleBinding().also { styleBinding = it }
        callbacks.onStyleChanged(this, DesktopStyle(binding, ::imageScale))
      }

      RuntimeEventType.MAP_LOADING_FINISHED -> callbacks.onMapFinishedLoading(this)

      RuntimeEventType.MAP_LOADING_FAILED -> {
        // The only channel for a URL style's failure, and the second channel for a malformed
        // inline one, which also throws from the setter. See applyRequestedStyle.
        val reason = event.message.ifBlank { "MapLibre failed to load the map" }
        logger?.e { "Map loading failed (code ${event.code}): $reason" }
        callbacks.onMapFailLoading(reason)
      }

      RuntimeEventType.MAP_CAMERA_WILL_CHANGE -> beginCameraMove()

      RuntimeEventType.MAP_CAMERA_IS_CHANGING -> callbacks.onCameraMoved(this)

      RuntimeEventType.MAP_CAMERA_DID_CHANGE -> {
        callbacks.onCameraMoved(this)
        // Not the end of anything, during a gesture. A drag is a stream of jumps, and MapLibre
        // raises a will-change and a did-change for each one, so ending the move here would report
        // a move that started and finished between two pointer samples. See endCameraMove.
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
            // Resumed after the drain rather than here: this event is queued immediately before
            // the transition's MAP_CAMERA_DID_CHANGE, so a caller resumed now would read the
            // camera one event too early.
            pendingResumes += waiter
          }
        }
      }

      RuntimeEventType.MAP_RENDER_ERROR ->
        logger?.e { "MapLibre render error: ${event.message.ifBlank { "unknown" }}" }

      RuntimeEventType.MAP_STYLE_IMAGE_MISSING ->
        // Logged rather than acted on, and that is where it stops for now: supplying the image
        // means a callback in the common API, and MapLibre Compose has none — neither the Android
        // nor the iOS adapter exposes one either, so a desktop-only hook would be a surface no
        // cross-platform code could call. Recorded in .agents/docs/COMMON_API_GAPS.md instead.
        // Logging it is
        // still worth doing: a missing sprite otherwise shows up as symbols that silently do not
        // draw, with nothing naming the image.
        logger?.d { "Style image missing: ${event.message}" }

      // Known, and deliberately not acted on. Named so that the branch below means "an event
      // this build has never seen" rather than "anything we happen not to use". MAP_IDLE in
      // particular no longer drives anything: the owner thread pumps on its own, so there is no
      // frame loop to park.
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
   * MapLibre's camera events are per change, and a gesture is many changes: a drag issues a jump
   * for every pointer sample, each with its own will-change and did-change. Reported literally,
   * that makes `isCameraMoving` true and false again within one drain of the event queue, which is
   * between two Compose frames — and Compose only ever shows a reader the value at recomposition,
   * so a flag that flickers below frame rate reads as permanently false. Anything watching for a
   * gesture, such as the Material 3 attribution button collapsing when the map is moved, then never
   * sees one.
   *
   * So a move here spans the gesture rather than the jump, which is also what the Android and iOS
   * SDKs report and therefore what the common API means. The reason is re-reported when it changes
   * so that a gesture taking over from an animation is not left labelled `PROGRAMMATIC` — the flag
   * is set from the UI thread while these events are handled on the owner thread, so the first
   * change of a drag can genuinely arrive before the gesture is known.
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

  /**
   * Reads back what MapLibre stored for a style image.
   *
   * Nothing in the public API needs this; it exists so a test can assert on the pixel ratio an
   * image was uploaded with, which is otherwise only observable by looking at the map.
   */
  internal fun styleImageInfo(imageId: String): StyleImageInfo? = runOnMap {
    it.styleImageInfo(imageId)
  }

  /**
   * The scale style images are rasterized at, which is the map's own.
   *
   * Taken from the loop rather than the map because it is fixed for that map's lifetime — a density
   * change builds a new loop — and because this is read while handling an event, where a blocking
   * hop back onto the thread raising it would be pointless.
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
   * The cap can only take the map below the rate the host presents at, because a map frame happens
   * inside a host frame — so it is a filter over an arriving cadence, not a clock of its own. That
   * makes the comparison a beat problem: a cap set to the display's own rate asks for exactly the
   * interval the frames already arrive at, and any interval measured even a microsecond short
   * rejects the frame, leaving the next one to arrive two periods later. Every second frame is
   * dropped and the map runs at half the rate that was asked for.
   *
   * [FRAME_INTERVAL_SLACK] is what stops that. It is a fraction of the requested interval rather
   * than a fixed duration because the tolerance that matters scales with the interval: a tenth of a
   * 120fps period is under a millisecond, while allowing that same absolute slack at 15fps would be
   * meaningless. It cannot let the map exceed the cap by more than that fraction, because the host
   * has no frame to give in between.
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

  // ───────────────────────────── dispatch ─────────────────────────────

  /**
   * Queues [action] for the map's owner thread, dropping it if there is no map.
   *
   * For things that act on the map as it is now — a pan, a zoom, cancelling a transition. There is
   * nothing to pan before a map exists, and nothing worth replaying onto a later one.
   */
  private fun onMap(action: (MapHandle) -> Unit) {
    loop?.post(action)
  }

  /**
   * Records [action] as part of the map's configuration under [key], and applies it now if there is
   * a map.
   *
   * For settings rather than actions, which have to survive two things a plain post does not. The
   * composable configures the camera and its limits before the surface has an extent, so before any
   * map exists; and a density change replaces the map underneath, which on Android would be a new
   * `MapAdapter` and so re-applied by `CameraState`, but here is invisible to Compose. [key] is
   * what makes the record last-write-wins rather than a growing log of every value ever set.
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

  // ───────────────────────────── MapAdapter ─────────────────────────────

  override fun setBaseStyle(style: BaseStyle) {
    if (style == requestedStyle) return
    requestedStyle = style
    // Reported before the new style is even requested, because the old one is already gone as far
    // as its content is concerned. This is what unloads the previous `SafeStyle` and disposes the
    // composition holding its sources and layers; without it that composition stays live and
    // recomposes against a style node whose base layers belong to the style being replaced — so an
    // anchor naming a layer of the *new* style fails to validate, and the crash lands in the
    // applier, mid-insert. Both mobile adapters report it from `setBaseStyle` for this reason;
    // desktop reported only the loaded style, which is why only desktop crashed on a style switch.
    callbacks.onStyleChanged(this, null)
    onMap(::applyRequestedStyle)
  }

  /** Applies whatever style was last asked for, on the map's owner thread. */
  private fun applyRequestedStyle(map: MapHandle) {
    val style = requestedStyle ?: return
    if (style == appliedStyle) return
    // setStyleJson parses inline, so a malformed style fails synchronously as well as queueing
    // MAP_LOADING_FAILED; setStyleUrl only fetches, so it reports through the event alone. A bad
    // style is caller input, not a bug here, and the queued event delivers onMapFailLoading on the
    // next drain, so it is caught rather than reported again.
    try {
      when (style) {
        is BaseStyle.Uri -> map.setStyleUrl(style.uri)
        is BaseStyle.Json -> map.setStyleJson(style.json)
      }
      appliedStyle = style
    } catch (error: MaplibreException) {
      // appliedStyle is deliberately not set: setting the same style again should retry rather
      // than be skipped as already applied. Nothing re-posts this on its own, so a style that
      // fails cannot spin the loop retrying it.
      logger?.e(error) { "Failed to apply style $style" }
    }
  }

  /**
   * The camera a caller asked for before the map existed.
   *
   * `MaplibreMap` applies its first camera as soon as it has an adapter, which is before the first
   * extent and so before there is a map to apply it to. Reading it back in that window answers with
   * what was asked for rather than MapLibre's default, which is otherwise a null island the caller
   * never requested.
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
    // Recorded as the fit rather than as a resolved camera: the fit depends on the viewport, and a
    // map replaced because the viewport changed should be fitted to the new one.
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
      // The caller's padding goes in through fitOptions; the padding that comes back describes
      // the fit that was computed, and applying it verbatim is what places the bounds correctly.
      fitOptions =
        CameraFitOptions().also {
          it.padding = padding.toEdgeInsets(layoutDirection)
          it.bearing = bearing
          it.pitch = tilt
        },
    )

  override suspend fun animateCameraPosition(finalPosition: CameraPosition, duration: Duration) {
    animate(duration) { map, animation ->
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
    animate(duration) { map, animation ->
      map.flyTo(cameraForBounds(map, boundingBox, bearing, tilt, padding), animation)
    }
  }

  /**
   * Starts a camera transition and suspends until MapLibre reports that it released the camera.
   *
   * Resumes normally however the transition ended — run to completion, superseded by a later
   * command, or cancelled — because the event carries identity rather than an outcome, and because
   * that matches what Android reports through `CancelableCallback.onCancel`.
   */
  private suspend fun animate(
    duration: Duration,
    start: (MapHandle, AnimationOptions) -> Unit,
  ): Unit = suspendCancellableCoroutine { continuation ->
    val started = runOnMap { map ->
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
        // A command MapLibre rejects starts no transition and emits no event, so a registration
        // left behind here would never be resolved.
        forgetTransition(id)
        throw error
      }
      // Registered after the command rather than before, so an already-cancelled coroutine
      // cancels the transition that was just started instead of leaving it running unwatched.
      continuation.invokeOnCancellation { abandonTransition(id) }
      true
    }
    // No map means nothing to animate, and nothing that could ever resume this.
    if (started != true) continuation.resume(Unit)
  }

  /** Supplies transition ids. Owner-thread state, like the two maps below. */
  private var lastTransitionId = 0L

  /**
   * The transition this session started most recently, cleared when its end is reported.
   *
   * MAP_CAMERA_TRANSITION_FINISHED says that a transition released the camera, not why, so this is
   * what tells "my animation is still the one driving the camera" from "a later command took it
   * over" — the question the old generation stamp existed to answer.
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
      // Its finish event still arrives and finds no waiter, which is logged rather than treated as
      // a fault. Guarded on being current so a late cancellation cannot stop a newer animation.
      if (wasCurrent) map.cancelTransitions()
    }
  }

  /**
   * Resolves everything awaiting a transition on a map that is going away.
   *
   * Closing a map discards its queued events, so no finish event will follow. Resumed rather than
   * cancelled: the transition genuinely ended, and a CancellationException raised into a caller
   * whose scope is still active would cancel that scope.
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
      // panning across the antimeridian, which is not what "no bounding box" asks for.
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
   *
   * `BoundOptions` is a field mask — an unset field leaves the map's current value alone — so
   * recording the four limits and the constraint separately is what lets a replacement map be told
   * only what was actually asked for.
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
      // Ours to own rather than an FFI gap: both mobile SDKs build the visible region in their
      // own language from corner projections, because the core exposes no such query. Android's
      // Projection.getVisibleRegion is pure Java over four fromScreenLocation calls, and iOS
      // reaches it through convertRect:toLatLngBoundsFromView:.
      //
      // latLngBoundsForCamera is not a substitute: it is axis-aligned, so it is wrong for a
      // rotated or pitched camera, while projecting the four corners is correct for both.
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
    // Throttling our own renderUpdate calls is the whole implementation, not a stand-in for one.
    // MapLibre produces no frames of its own here — a frame exists only because the host asked for
    // one — so the call rate is the frame rate. Android and iOS cap it the same way, in their own
    // language, rather than through anything the core provides.
    maximumFps = value.maximumFps
    // Configuration rather than an action: the overlays are a setting the composable states once,
    // so a map that does not exist yet, or one that replaces this one, has to be told.
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
    // Desktop draws no ornaments; MapLibre Native has no compass, scale bar, or attribution widget
    // outside the mobile SDKs, so there is nothing to forward.
  }

  override fun setGestureSettings(value: GestureOptions) {
    // Gestures are implemented in Compose rather than by MapLibre Native, so the options are read
    // by the input handling in DesktopMapView rather than pushed into the map.
  }

  // Projected against the live map rather than through MapHandle.createProjection. That returns a
  // MapProjectionHandle, which is a snapshot: it copies the transform at creation and never follows
  // the map afterwards. CameraProjection is a live view — it also answers rendered feature queries,
  // which a snapshot cannot do at all — so a handle held across a camera move would quietly return
  // stale coordinates.
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
   * Runs a rendered-feature query on the current render session.
   *
   * Rendered feature state belongs to the session rather than the map, so a query before the first
   * frame or during surface loss has nothing to answer from. That returns empty with a debug log
   * rather than throwing: a click landing in the gap between a resize and the next frame is
   * ordinary, not a caller error.
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
      // Uncaught for the same reason as StyleBinding.withRenderSession: past the null check there
      // is no failure here that is not a bug.
      session.queryRenderedFeatures(geometry, renderedQueryOptions(layerIds, predicate)).map {
        it.toGeoJsonFeature()
      }
    } ?: emptyList()

  /**
   * Meters per logical pixel at [latitude], for the map's current zoom.
   *
   * Ours to own rather than an FFI gap. `mbgl::Projection::getMetersPerPixelAtLatitude` is a
   * stateless static that takes a latitude and a zoom and touches no map, so both mobile SDKs
   * implement this the same way: iOS forwards to it with `self.zoomLevel`, and Android's JNI shim
   * does nothing else either. Supplying the map's zoom is the only part that needs a map.
   *
   * Transcribed rather than approximated, clamps included, because a scale bar drawn from a subtly
   * different formula is wrong in a way nobody notices. Note the 512px tile size rather than the
   * more common 256.
   */
  override fun metersPerDpAtLatitude(latitude: Double): Double {
    val zoom = getCameraPosition().zoom.coerceIn(MIN_PROJECTION_ZOOM, MAX_PROJECTION_ZOOM)
    val clamped = latitude.coerceIn(-MERCATOR_MAX_LATITUDE, MERCATOR_MAX_LATITUDE)
    return cos(clamped * PI / 180.0) * EARTH_CIRCUMFERENCE_METERS / (2.0.pow(zoom) * TILE_SIZE)
  }

  // ───────────────────────────── input, called from Compose ─────────────────────────────

  fun onGestureStarted() {
    setGestureInProgress(true)
  }

  fun onGestureEnded() {
    setGestureInProgress(false)
  }

  /**
   * Records that a gesture is or is not running, here and on the map.
   *
   * The local flag is what classifies a camera move as [CameraMoveReason.GESTURE], and it is read
   * from the owner thread while Compose writes it, so it stays `@Volatile` rather than moving onto
   * the map entirely. The map's own flag is what mbgl consults to treat the commands issued in
   * between as one live gesture instead of a series of unrelated camera changes.
   *
   * The flag stays set until it is cleared, so every `true` needs its `false` — including the
   * cancellation paths, which is why this is called from [onGestureEnded] rather than only from the
   * end of a successful drag.
   */
  private fun setGestureInProgress(active: Boolean) {
    isGestureInProgress = active
    // Posted rather than blocking: the answer is not needed, and a gesture must never wait on the
    // owner thread to finish a style parse before the pointer can move.
    loop?.post { map ->
      map.isGestureInProgress = active
      // The end of the gesture is the end of the move, because the jumps it was made of stopped
      // reporting their own ends when it began. Posted with the flag rather than done here so it
      // runs on the thread that owns that bookkeeping, and after every jump the gesture queued.
      if (!active) endCameraMove()
    }
  }

  /**
   * Pans by a delta, over [duration].
   *
   * A zero duration is a jump, which is what a drag wants: it is already tracking the pointer, and
   * animating toward a position the pointer has since left would lag behind it. A discrete input —
   * an arrow key, a double click — has no such continuous source, so it eases instead. See
   * [INPUT_ANIMATION_DURATION].
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
   * Rotates and pitches together from one drag.
   *
   * A single `jumpTo` rather than the FFI's two-point `rotateBy`: that call derives an angle
   * between two pointer positions and is meant for a two-finger gesture, so driving it from a
   * horizontal mouse drag rotates around the wrong centre and fights the separate pitch change.
   * Horizontal movement turns the bearing and vertical movement raises the pitch, which is what the
   * maplibre-native-ffi Compose example does and what the previous desktop implementation did.
   *
   * Deltas are in logical pixels.
   */
  fun rotateAndPitchBy(deltaX: Double, deltaY: Double) {
    // A delta rather than a target, applied on the owner thread, because reading the current
    // camera and writing the new one has to happen together on the thread that owns the map.
    onMap { map ->
      val camera = map.camera
      map.jumpTo(
        CameraOptions().also {
          it.bearing = (camera.bearing ?: 0.0) + deltaX * DRAG_ROTATE_DEGREES_PER_PIXEL
          it.pitch =
            ((camera.pitch ?: 0.0) - deltaY * DRAG_PITCH_DEGREES_PER_PIXEL).coerceIn(
              MIN_PITCH_DEGREES,
              MAX_PITCH_DEGREES,
            )
        }
      )
    }
  }

  /**
   * Reports a click at [offset], in logical pixels.
   *
   * Projection happens on the owner thread and only the resulting immutable position crosses back,
   * so the callback never touches a native handle.
   */
  fun onPrimaryClick(offset: DpOffset) {
    val position = runOnMap { it.latLngForPixel(offset.toScreenPoint()).toPosition() } ?: return
    callbacks.onClick(this, position, offset)
  }

  /**
   * Reports a secondary click at [offset] as a long click.
   *
   * Desktop has no press-and-hold convention, so the secondary button stands in for the long press
   * the mobile SDKs use. This is what the previous desktop implementation did.
   */
  fun onSecondaryClick(offset: DpOffset) {
    val position = runOnMap { it.latLngForPixel(offset.toScreenPoint()).toPosition() } ?: return
    callbacks.onLongClick(this, position, offset)
  }

  fun cancelTransitions() {
    onMap { map ->
      // Any outstanding transition ends here, and its finish event resumes whoever was awaiting
      // it; clearing the id first is what stops a later cancellation from stopping a newer one.
      currentTransitionId = null
      map.cancelTransitions()
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
