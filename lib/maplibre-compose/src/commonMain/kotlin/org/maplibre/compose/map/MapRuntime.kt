@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraAnchor
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.camera.forPath
import org.maplibre.compose.camera.internal.CameraCommandGuard
import org.maplibre.compose.camera.internal.CameraInputAuthority
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.interaction.internal.RecognizedMapInput
import org.maplibre.compose.interaction.internal.select
import org.maplibre.compose.layers.LayerHandle
import org.maplibre.compose.layers.layerHandle
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.offline.OfflineManager
import org.maplibre.compose.offline.OfflineManagerBackend
import org.maplibre.compose.offline.RuntimeBoundOfflineManager
import org.maplibre.compose.offline.UnsupportedOfflineManager
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceHandle
import org.maplibre.compose.sources.sourceHandle
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LayerSummary
import org.maplibre.compose.style.Light
import org.maplibre.compose.style.Projection
import org.maplibre.compose.style.Sky
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.scaledBy
import org.maplibre.compose.style.systemAnimatorDurationScale
import org.maplibre.compose.style.withScaledTransitions
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.ImageStretch
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.compose.util.positions
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.Position

/**
 * Configuration for one [MapRuntime].
 *
 * Every platform accepts a request interceptor and a resource provider, fixed for the lifetime of
 * the runtime. They apply to its maps, snapshotters, and supported offline operations. The MapLibre
 * Native platforms also accept a cache file and a cache size limit.
 */
public expect class MapRuntimeOptions

/** The options a runtime uses when nothing configures it. */
internal expect fun defaultMapRuntimeOptions(): MapRuntimeOptions

/** Creates a runtime from [options]. The caller must close the result. */
public expect fun createMapRuntime(options: MapRuntimeOptions): MapRuntime

/**
 * The main dispatcher when one is installed, so engine callbacks reach map state on the thread that
 * reads it. Without one, callbacks run inline on the engine thread as before.
 */
internal fun platformMainDispatcher(): CoroutineDispatcher =
  try {
    Dispatchers.Main.immediate.also { it.isDispatchNeeded(EmptyCoroutineContext) }
  } catch (_: IllegalStateException) {
    Dispatchers.Unconfined
  }

/** Creates logical maps that share one application-level configuration. */
public interface MapRuntime {
  /** The offline packs and ambient cache managed by this runtime. */
  public val offlineManager: OfflineManager

  /**
   * Creates a logical map with [baseStyle] and the sources, layers, and images that [content]
   * declares. The caller must close the result.
   *
   * [content] reads the returned state through [LocalMapState] and its viewport through
   * [LocalViewport].
   */
  public fun createMapState(
    baseStyle: BaseStyle,
    cameraPosition: CameraPosition = CameraPosition(),
    content: @Composable @MaplibreComposable () -> Unit = {},
  ): MapState

  /**
   * Creates an independent non-UI map with [baseStyle] and the sources, layers, and images that
   * [content] declares, for image capture. The caller must close the result.
   *
   * [content] reads the viewport of each capture request through [LocalViewport]. It has no
   * [MapState], so [LocalMapState] is null.
   */
  public fun createSnapshotter(
    baseStyle: BaseStyle,
    content: @Composable @MaplibreComposable () -> Unit = {},
  ): MapSnapshotter

  /** Returns true after [close] marks this runtime as closed. */
  public val isClosed: Boolean

  /** Marks this runtime as closed and starts child and shared-resource cleanup. */
  public fun close()

  /**
   * Waits until every child and shared resource has finished cleanup.
   *
   * @throws MapCleanupException if cleanup fails.
   */
  public suspend fun awaitClosed()
}

/** Reports the load state for the desired base style of one logical map. */
public sealed interface StyleLoadState {
  /** No map surface can currently load the desired style. */
  public data object Pending : StyleLoadState

  /** Indicates that the current map surface is loading the desired style. */
  public data object Loading : StyleLoadState

  /**
   * The base style and initial composed content are ready. Ordinary content updates preserve this
   * state; it does not indicate that tiles or animations have finished rendering.
   */
  public data object Ready : StyleLoadState

  /** Loading the style or applying its composed content failed. A later revision may recover. */
  public data class Failed(public val reason: String?) : StyleLoadState
}

internal interface MapStyleStateOwner {
  fun setBaseStyle(value: BaseStyle)

  fun desiredSourceDefinition(id: String): org.maplibre.compose.style.SourceDefinition?

  fun isSourceWritable(id: String): Boolean

  fun isLayerWritable(id: String): Boolean

  fun isImageWritable(id: String): Boolean

  fun requireSourceWritable(id: String)

  fun requireLayerWritable(id: String)

  fun addStyleSource(source: Source): SourceHandle

  fun removeStyleSource(id: String, expectedStyle: StyleBinding, identity: Any): Boolean

  fun addStyleImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    stretch: ImageStretch?,
    expectedStyle: StyleBinding? = null,
  ): StyleImageHandle

  fun removeStyleImage(id: String, expectedStyle: StyleBinding, identity: Any): Boolean

  fun readyLoadedStyle(): StyleBinding?

  fun <T> runStyleHandleOperation(binding: StyleBinding, action: () -> T): T
}

/** Desired and applied style state for one logical map or snapshotter. */
public class MapStyleState internal constructor(baseStyle: BaseStyle) {
  private var owner: MapStyleStateOwner? = null
  private val loadedStyle = AtomicReference<StyleBinding?>(null)
  private var sourcesState: Map<String, SourceHandle> by
    mutableStateOf(emptyMap(), referentialEqualityPolicy())
  private var layersState: Map<String, LayerHandle> by
    mutableStateOf(emptyMap(), referentialEqualityPolicy())
  private var baseStyleState: BaseStyle by mutableStateOf(baseStyle, structuralEqualityPolicy())

  internal var baseStyleDeclared: Boolean = false

  /** The desired base style. A change replaces generation-bound resources. */
  public val baseStyle: BaseStyle
    get() = baseStyleState

  /** Base-style commands, or null when [rememberMapState] owns the base style. */
  public val asMutable: MutableMapStyleState?
    get() = if (baseStyleDeclared) null else MutableMapStyleState(this)

  internal fun updateBaseStyle(value: BaseStyle) {
    owner?.setBaseStyle(value) ?: setBaseStyleState(value)
  }

  public var loadState: StyleLoadState by mutableStateOf(StyleLoadState.Pending)
    internal set

  /** Sources in the current loaded-style generation. */
  public val sources: StyleSources = StyleSources(this)

  /** Layers in the current loaded-style generation. */
  public val layers: StyleLayers = StyleLayers(this)

  /** Style-image commands for the current loaded-style generation. */
  public val images: StyleImages = StyleImages(this)

  /** Global transition of the current loaded-style generation. */
  public val transition: StyleTransition = StyleTransition(this)

  /** Global expression values of the current loaded-style generation. */
  public val globalState: StyleGlobalState = StyleGlobalState(this)

  /** Light of the current loaded-style generation. */
  public val light: StyleLight = StyleLight(this)

  /** Sky of the current loaded-style generation. */
  public val sky: StyleSky = StyleSky(this)

  /** Projection of the current loaded-style generation. */
  public val projection: StyleProjection = StyleProjection(this)

  internal suspend fun globalStateValues(): JsonObject? = readStyle { it.globalState() }

  internal fun setGlobalStateProperty(name: String, value: JsonElement) {
    mutateStyle { it.setGlobalStateProperty(name, value) }
  }

  internal suspend fun transitionOptions(): TransitionOptions? = readStyle { it.transition() }

  internal fun setTransitionOptions(options: TransitionOptions) {
    mutateStyle { it.setTransition(options.scaledBy(it.animatorDurationScale)) }
  }

  internal suspend fun placementTransitions(): Boolean? = readStyle { it.placementTransitions() }

  internal fun setPlacementTransitions(enabled: Boolean) {
    mutateStyle { it.setPlacementTransitions(enabled) }
  }

  internal suspend fun lightProperty(name: String): JsonElement? = readStyle {
    it.lightProperty(name)
  }

  internal fun setLight(light: Light) {
    mutateStyle { it.setLight(light.toJson().withScaledTransitions(it.animatorDurationScale)) }
  }

  internal suspend fun skyProperty(name: String): JsonElement? = readStyle { it.skyProperty(name) }

  internal fun setSky(sky: Sky?) {
    mutateStyle { it.setSky(sky?.toJson()?.withScaledTransitions(it.animatorDurationScale)) }
  }

  internal suspend fun projectionProperty(name: String): JsonElement? = readStyle {
    it.projectionProperty(name)
  }

  internal fun setProjection(projection: Projection) {
    mutateStyle { it.setProjection(projection.toJson()) }
  }

  /**
   * Reads from the ready loaded style, or returns null without one. A style that stops being ready
   * while the engine answers also reads as null: the value belongs to a generation that is gone.
   */
  private suspend fun <T> readStyle(read: suspend (StyleBinding) -> T?): T? {
    val current = readyLoadedStyle() ?: return null
    operationGuard(current).run {}
    val result = read(current)
    return result.takeIf { readyLoadedStyle() === current }
  }

  /** Posts a write to the ready loaded style. The engine reports a rejection through the logger. */
  private fun mutateStyle(mutate: (StyleBinding) -> Unit) {
    val current = checkNotNull(readyLoadedStyle()) { "No ready loaded style" }
    operationGuard(current).run { mutate(current) }
  }

  internal fun sourceHandle(id: String): SourceHandle? {
    if (readyLoadedStyle() == null) return null
    return sourcesState[id]
  }

  private fun sourceHandle(current: StyleBinding, id: String): SourceHandle? = owner.let { owner ->
    val definition = owner?.desiredSourceDefinition(id)
    val identity = current.identity.sources.get(id)
    current.sourceHandle(
      id = id,
      definition = definition,
      currentDefinition = { owner?.desiredSourceDefinition(id) },
      isCurrentResource = { current.identity.sources.isCurrent(id, identity) },
      operations = operationGuard(current),
    )
  }

  internal fun layerHandle(id: String): LayerHandle? {
    if (readyLoadedStyle() == null) return null
    return layersState[id]
  }

  internal fun readyLoadedStyle(): StyleBinding? {
    if (loadState != StyleLoadState.Ready) return null
    // With an owner attached, its serialized check is the only authority: a plain fallback to the
    // stored reference could return a binding the owner has already moved to Loading.
    val owner = owner ?: return loadedStyle.load()
    return owner.readyLoadedStyle()
  }

  internal fun attach(owner: MapStyleStateOwner) {
    this.owner = owner
  }

  internal fun requireOwner(): MapStyleStateOwner = checkNotNull(owner)

  internal fun setBaseStyleState(value: BaseStyle) {
    baseStyleState = value
  }

  internal fun updateLoadedStyle(style: StyleBinding?) {
    loadedStyle.store(style)
    sourcesState = emptyMap()
    layersState = emptyMap()
  }

  internal fun invalidateLoadedStyle() {
    loadedStyle.exchange(null)?.invalidate()
    sourcesState = emptyMap()
    layersState = emptyMap()
  }

  internal fun isCurrentLoadedStyle(style: StyleBinding): Boolean = loadedStyle.load() === style

  internal fun currentLoadedStyle(): StyleBinding? = loadedStyle.load()

  internal fun refreshResources() {
    val current = loadedStyle.load()
    if (loadState != StyleLoadState.Ready || current == null) {
      sourcesState = emptyMap()
      layersState = emptyMap()
    } else {
      updateResources(readResources(current))
    }
  }

  internal fun readResources(current: StyleBinding): LoadedStyleResources =
    LoadedStyleResources(readSources(current), readLayers(current))

  internal fun readSources(
    current: StyleBinding,
    changedId: String? = null,
  ): Map<String, SourceHandle> {
    if (changedId != null) {
      val handle = sourceHandle(current, changedId)
      val handles = sourcesState.toMutableMap()
      if (handle == null) handles.remove(changedId) else handles[changedId] = handle
      val ids = current.sourceIds()
      current.identity.sources.retain(ids.toSet())
      return ids.mapNotNull { id -> handles[id]?.let { id to it } }.toMap()
    }
    val ids = current.sourceIds().toSet()
    current.identity.sources.retain(ids)
    return ids.mapNotNull { id -> sourceHandle(current, id)?.let { id to it } }.toMap()
  }

  internal fun updateSources(sources: Map<String, SourceHandle>) {
    sourcesState = sources
  }

  internal fun readLayers(current: StyleBinding): Map<String, LayerHandle> {
    val summaries = current.layerSummaries()
    current.identity.layers.retain(summaries.keys)
    return summaries.mapValues { (id, summary) -> layerHandle(current, id, summary) }
  }

  /** Rereads the handles of [ids] in one engine round trip; a removed layer maps to null. */
  internal fun readLayers(current: StyleBinding, ids: Set<String>): Map<String, LayerHandle?> {
    if (ids.isEmpty()) return emptyMap()
    val summaries = current.layerSummaries()
    return ids.associateWith { id -> summaries[id]?.let { layerHandle(current, id, it) } }
  }

  internal fun layerHandle(current: StyleBinding, id: String, summary: LayerSummary): LayerHandle {
    val identity = current.identity.layers.get(id)
    return current.layerHandle(
      id,
      summary,
      isCurrentResource = { current.identity.layers.isCurrent(id, identity) },
      operations = operationGuard(current),
    )
  }

  internal fun updateLayers(handles: Map<String, LayerHandle?>, order: List<String>) {
    val updated = layersState.toMutableMap()
    handles.forEach { (id, handle) ->
      if (handle == null) updated.remove(id) else updated[id] = handle
    }
    layersState = order.mapNotNull { id -> updated[id]?.let { id to it } }.toMap()
  }

  internal fun updateResources(resources: LoadedStyleResources) {
    sourcesState = resources.sources
    layersState = resources.layers
  }

  internal fun sourceHandles(): Map<String, SourceHandle> =
    if (readyLoadedStyle() == null) emptyMap() else sourcesState

  internal fun layerHandles(): Map<String, LayerHandle> =
    if (readyLoadedStyle() == null) emptyMap() else layersState

  internal fun operationGuard(style: StyleBinding): StyleHandleOperationGuard =
    object : StyleHandleOperationGuard {
      override fun <T> run(action: () -> T): T =
        owner?.runStyleHandleOperation(style, action) ?: action()

      override fun isSourceWritable(id: String): Boolean = owner?.isSourceWritable(id) == true

      override fun isLayerWritable(id: String): Boolean = owner?.isLayerWritable(id) == true

      override fun removeSource(id: String, identity: Any): Boolean =
        requireOwner().removeStyleSource(id, style, identity)

      override fun requireSourceWritable(id: String) {
        owner?.requireSourceWritable(id)
      }

      override fun requireLayerWritable(id: String) {
        owner?.requireLayerWritable(id)
      }
    }
}

internal data class LoadedStyleResources(
  val sources: Map<String, SourceHandle>,
  val layers: Map<String, LayerHandle>,
)

internal class ImperativeSourceRecord(val definition: SourceDefinition)

internal class ImperativeImageRecord(val fromResolver: Boolean = false)

/**
 * One missing-image resolution, identified by [token] so a stale one cannot evict its successor.
 */
internal class MissingImageResolution(val token: Any, val work: Deferred<Unit>)

internal class StyleMutationReservation {
  val completion = CompletableDeferred<Unit>()
}

/** Connects a [MapState] to one map surface for the lifetime of one render lease. */
internal class MapAttachment
internal constructor(
  private val owner: MapAttachmentAuthority,
  internal val token: MapPresentationToken,
  internal val adapter: MapAdapter,
) {
  private val invalidated = CompletableDeferred<Unit>()
  private var validState: Boolean by mutableStateOf(true)
  private var viewportState: Viewport? by mutableStateOf(null)
  private var gestureActiveState: Boolean by mutableStateOf(false)
  private var activeCameraChanges: Int by mutableIntStateOf(0)
  private var moveReasonState: CameraMoveReason by mutableStateOf(CameraMoveReason.NONE)
  private var engagedState: Boolean by mutableStateOf(false)
  val isValid: Boolean
    get() = validState

  val isEngaged: Boolean
    get() = engagedState

  val viewport: Viewport?
    get() = viewportState

  val isCameraMoving: Boolean
    get() = gestureActiveState || activeCameraChanges > 0

  val cameraMoveReason: CameraMoveReason
    get() = moveReasonState

  suspend fun cameraForBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition = runLeaseBound {
    awaitViewportState()
    adapter.cameraForBounds(boundingBox, bearing, tilt, cameraPadding, fitPadding)
  }

  suspend fun cameraForGeometry(
    geometry: Geometry,
    bearing: Double,
    tilt: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
  ): CameraPosition = runLeaseBound {
    awaitViewportState()
    adapter.cameraForGeometry(geometry, bearing, tilt, cameraPadding, fitPadding)
  }

  suspend fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    guard: CameraCommandGuard?,
  ): Unit = runLeaseBound {
    awaitViewportState()
    guard?.awaitDispatchTurn()
    adapter.fitCameraToBounds(
      boundingBox,
      bearing,
      tilt,
      cameraPadding,
      fitPadding,
      boundGuard(guard),
    )
  }

  suspend fun animateCamera(
    update: CameraUpdate,
    animation: CameraAnimation = CameraAnimation.Ease(),
    guard: CameraCommandGuard? = null,
  ): Unit = runLeaseBound {
    awaitViewportState()
    guard?.awaitDispatchTurn()
    adapter.animateCamera(
      update,
      animation.forPathTo(update.applyTo(adapter.getCameraPosition())),
      boundGuard(guard),
    )
  }

  suspend fun animateCameraAround(
    anchor: CameraAnchor,
    zoom: Double?,
    bearing: Double?,
    tilt: Double?,
    animation: CameraAnimation.Ease,
    guard: CameraCommandGuard? = null,
  ): Unit = runLeaseBound {
    awaitViewportState()
    guard?.awaitDispatchTurn()
    adapter.animateCameraAround(anchor, zoom, bearing, tilt, animation, boundGuard(guard))
  }

  suspend fun animateCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double,
    tilt: Double,
    cameraPadding: DpPadding?,
    fitPadding: DpPadding,
    animation: CameraAnimation,
    guard: CameraCommandGuard?,
  ): Unit = runLeaseBound {
    awaitViewportState()
    guard?.awaitDispatchTurn()
    val target = adapter.cameraForBounds(boundingBox, bearing, tilt, cameraPadding, fitPadding)
    adapter.animateCameraToBounds(
      boundingBox,
      bearing,
      tilt,
      cameraPadding,
      fitPadding,
      animation.forPathTo(target),
      boundGuard(guard),
    )
  }

  /**
   * Resolves [CameraAnimation.forPath] against the zoom the map will apply. The engines also keep
   * the center inside a bounding box constraint, which is not mirrored here. The receiver arrives
   * already scaled by the animator duration scale, so a fallback ease it turns into is scaled here.
   */
  private fun CameraAnimation.forPathTo(target: CameraPosition): CameraAnimation {
    val constraints = adapter.getCameraConstraints()
    val constrained =
      target.copy(zoom = target.zoom.coerceIn(constraints.minZoom, constraints.maxZoom))
    val resolved = forPath(adapter.getCameraPosition(), constrained)
    return if (resolved === this) this else resolved.scaledBy(systemAnimatorDurationScale())
  }

  fun getVisibleRegion(): VisibleRegion? = withViewport { it.getVisibleRegion() }

  fun getVisibleBounds(): VisibleBounds? = withViewport { it.getVisibleBounds() }

  fun screenLocationFromPosition(position: Position): DpOffset? = withViewport {
    it.screenLocationFromPosition(position)
  }

  fun overlayScreenLocationFromPosition(position: Position): DpOffset? = withViewport {
    it.overlayScreenLocationFromPosition(position)
  }

  fun positionFromScreenLocation(offset: DpOffset): Position? = withViewport {
    it.positionFromScreenLocation(offset)
  }

  fun metersPerDpAtLatitude(latitude: Double): Double? = withViewport {
    it.metersPerDpAtLatitude(latitude)
  }

  suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> = runLeaseBound {
    awaitViewportState()
    adapter.queryRenderedFeatures(offset, layerIds, predicate.compileOrNull())
  }

  suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> = runLeaseBound {
    awaitViewportState()
    adapter.queryRenderedFeatures(rect, layerIds, predicate.compileOrNull())
  }

  internal fun updateViewport(value: Viewport?) {
    run {
      viewportState = value
      owner.viewportPublished(this, value)
    }
  }

  /**
   * A gesture sets the reason even while an engine camera change is in flight, because the gesture
   * token decides whether a change belongs to the user.
   */
  internal fun setGestureActive(active: Boolean) {
    run {
      gestureActiveState = active
      if (active) moveReasonState = CameraMoveReason.GESTURE
    }
  }

  internal fun setEngaged(engaged: Boolean) {
    engagedState = engaged
  }

  internal fun cameraChangeStarted() {
    run {
      activeCameraChanges++
      if (!gestureActiveState) moveReasonState = CameraMoveReason.PROGRAMMATIC
    }
  }

  internal fun cameraChangeEnded() {
    // Native ends each command separately. An inset update can end while a zoom is still moving.
    activeCameraChanges = (activeCameraChanges - 1).coerceAtLeast(0)
  }

  internal fun abandonCameraChanges() {
    activeCameraChanges = 0
  }

  internal fun invalidate() {
    owner.gestureAuthority.detach(this)
    run {
      validState = false
      viewportState = null
      gestureActiveState = false
      activeCameraChanges = 0
      engagedState = false
    }
  }

  /**
   * Fails lease-bound operations parked on this attachment. Callers on an immediate dispatcher
   * resume inline, so this runs after the owner has replaced the attachment and closed its
   * snapshot.
   */
  internal fun cancelLeaseBoundOperations() {
    invalidated.complete(Unit)
  }

  private fun Expression<BooleanValue>.compileOrNull(): CompiledExpression<BooleanValue>? {
    if (this == const(true)) return null
    return compile(ExpressionContext.None)
  }

  private fun <T> withViewport(block: (MapAdapter) -> T): T? =
    owner.withCurrentOrNull(this) { if (viewportState == null) null else block(adapter) }

  private fun boundGuard(guard: CameraCommandGuard?): CameraCommandGuard =
    object : CameraCommandGuard {
      override fun isValid(): Boolean =
        owner.isCurrent(this@MapAttachment) && guard?.isValid() != false

      override fun dispatched() {
        guard?.dispatched()
      }
    }

  private suspend fun awaitViewportState(): Viewport = owner.awaitViewport(this)

  private suspend fun <T> runLeaseBound(block: suspend () -> T): T = coroutineScope {
    if (!owner.isCurrent(this@MapAttachment)) throw MapAttachmentChangedException()
    val operation =
      async(start = CoroutineStart.UNDISPATCHED) {
        if (!owner.isCurrent(this@MapAttachment)) throw MapAttachmentChangedException()
        block()
      }
    select {
      operation.onAwait { result ->
        // An inline operation can finish after invalidation, before select starts listening.
        if (!owner.isCurrent(this@MapAttachment)) throw MapAttachmentChangedException()
        result
      }
      invalidated.onAwait {
        operation.cancelAndJoin()
        throw MapAttachmentChangedException()
      }
    }
  }
}

internal class MapAttachmentChangedException :
  CancellationException("The map attachment changed during the operation")

/**
 * Holds the observable style, camera, and map operations for one logical map.
 *
 * Use it from the main thread. Engine callbacks reach it there too, through the runtime's main
 * dispatcher.
 */
@Stable
public class MapState
internal constructor(
  internal val runtime: RuntimeImplementation,
  cameraPosition: CameraPosition,
  baseStyle: BaseStyle,
  content: @Composable @MaplibreComposable () -> Unit,
) {
  internal val styleContent: @Composable @MaplibreComposable () -> Unit = {
    CompositionLocalProvider(LocalMapState provides this, LocalViewport provides viewport) {
      content()
    }
  }
  internal val lifecycle =
    MapLifecycleAuthority(this, runtime.physicalScope, runtime.mainDispatcher)
  internal val styleAuthority = MapStyleAuthority(lifecycle, runtime, baseStyle)
  public val style: MapStyleState = styleAuthority.style
  internal val gestureAuthority = CameraInputAuthority(this)
  internal val attachmentAuthority =
    MapAttachmentAuthority(lifecycle, gestureAuthority, styleAuthority, cameraPosition)
  /** Set by the current presentation; recognized gestures are ignored without one. */
  internal var recognizedInput: RecognizedMapInput? = null

  /** Current camera state. Padding excludes the presentation's viewport insets. */
  public val cameraPosition: CameraPosition
    get() = attachmentAuthority.cameraPosition

  internal val currentMapAttachment: MapAttachment?
    get() = attachmentAuthority.current

  /** Contains the current rendered viewport, or null while no viewport is available. */
  public val viewport: Viewport?
    get() = currentMapAttachment?.viewport

  /**
   * Returns true while a gesture holds the camera or an engine camera change is in flight. During a
   * drag the gesture stays active across every camera change the engine reports, so the value stays
   * true for the whole drag.
   */
  public val isCameraMoving: Boolean
    get() = currentMapAttachment?.isCameraMoving == true

  /**
   * Contains what started the most recent camera movement, or [CameraMoveReason.NONE] while
   * detached and before the first movement. The value stays after the movement ends.
   */
  public val cameraMoveReason: CameraMoveReason
    get() = currentMapAttachment?.cameraMoveReason ?: CameraMoveReason.NONE

  /**
   * Returns true while the focused map consumes the keys that pan, zoom, rotate, and tilt. Enter,
   * numpad Enter, D-pad center, and a pointer press engage the map. Escape disengages it, and Back
   * disengages it when a key engaged it. Focus loss disengages it. A focused map that is not
   * engaged passes direction keys to focus traversal. The value is false while no map surface is
   * attached.
   */
  public val isEngaged: Boolean
    get() = currentMapAttachment?.isEngaged == true

  /**
   * Reports [MapEvent]s that occur after collection starts. Past events are not replayed, and slow
   * collectors may miss events.
   *
   * Style and idle events can continue while a native map has no attached surface. Camera and frame
   * events require an attached surface.
   *
   * Unconfined collectors may run inside engine callbacks. Use a dispatcher that queues execution
   * for collectors that call map commands such as [StyleImages.add].
   */
  public val events: Flow<MapEvent> = attachmentAuthority.events

  /**
   * Supplies missing style images on demand. Null (the default) disables resolution.
   *
   * Return a [ResolvedStyleImage] for the requested ID, suspending if it needs to be loaded. Be
   * prepared to supply the same ID again after the map discards unused images. On native maps,
   * resolved images may appear only after the affected tiles are laid out again.
   *
   * Return null for IDs you cannot supply. Null results and exceptions are not retried until the
   * base style reloads or the resolver is replaced.
   *
   * The resolver is called on the main thread. Replacing or clearing this property does not cancel
   * calls already running.
   */
  public var missingImageResolver: MissingImageResolver?
    get() = styleAuthority.missingImageResolver
    set(value) {
      styleAuthority.missingImageResolver = value
    }

  public val isClosed: Boolean
    get() = attachmentAuthority.isClosed

  /** Marks this state as closed and starts cleanup of the current map surface. */
  public fun close(): Unit = lifecycle.close()

  /**
   * Waits until map-surface cleanup has completed.
   *
   * @throws MapCleanupException if cleanup fails.
   */
  public suspend fun awaitClosed(): Unit = lifecycle.awaitClosed()

  /**
   * Sets the durable camera position and applies it to the current surface when one is attached.
   */
  public fun setCameraPosition(position: CameraPosition): Unit =
    attachmentAuthority.setCameraPosition(position)

  /**
   * Stops camera movement at the position reached when the backend processes this command.
   *
   * Cancels camera commands waiting for a viewport, running animations, gesture momentum, and the
   * current recognized gesture's camera control. Interrupted suspend calls throw
   * [kotlinx.coroutines.CancellationException]. New input or camera commands can move the camera
   * again; a newer command takes precedence over this stop. Pointer events are not cancelled: a
   * press that has not yet become a recognized gesture may still start one after this call.
   *
   * Does not wait for a surface to attach. Without a surface, the retained camera is unchanged.
   * With a surface, [cameraPosition] updates when the backend reports the stopped position.
   */
  public fun stopCameraMovement() {
    val guard = gestureAuthority.beginProgrammatic()
    val attachment = run {
      requireOpenLocked()
      if (!guard.isValid()) return
      currentMapAttachment ?: return
    }
    attachment.adapter.stopCameraMovement(
      CameraCommandGuard { attachmentAuthority.isCurrent(attachment) && guard.isValid() }
    )
  }

  /**
   * Waits for a viewport, then calculates a camera for [boundingBox] without moving the map or
   * interrupting camera input or animations. Detaching the surface during the query cancels it.
   *
   * [cameraPadding] sets the returned camera's padding; null retains the current padding.
   * [fitPadding] adds a temporary margin inside the viewport insets and camera padding.
   *
   * The result uses the current viewport size, insets, and camera constraints. Recalculate it if
   * those change before applying it.
   *
   * @throws IllegalStateException if the backend cannot calculate a camera for the bounds.
   */
  public suspend fun cameraForBounds(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    cameraPadding: DpPadding? = null,
    fitPadding: DpPadding = DpPadding.Zero,
  ): CameraPosition =
    attachmentAuthority
      .awaitAttachment()
      .cameraForBounds(boundingBox, bearing, tilt, cameraPadding, fitPadding)

  /**
   * Waits for a viewport, then calculates a camera that fits every position of [geometry] without
   * moving the map or interrupting camera input or animations. Detaching the surface during the
   * query cancels it.
   *
   * Unlike [cameraForBounds], the fit follows the positions themselves rather than their bounding
   * box, so a rotated camera leaves no extra space around a diagonal route. With a bearing of zero
   * and a tilt of zero, both queries produce the same camera.
   *
   * Positions are used as given. Express a route that crosses the antimeridian with continuous
   * longitudes, such as 179 followed by 181; the query does not unwrap longitudes itself.
   *
   * See [cameraForBounds] for padding and viewport semantics.
   *
   * @throws IllegalArgumentException if [geometry] contains no positions.
   * @throws IllegalStateException if the backend cannot calculate a camera for the geometry.
   */
  public suspend fun cameraForGeometry(
    geometry: Geometry,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    cameraPadding: DpPadding? = null,
    fitPadding: DpPadding = DpPadding.Zero,
  ): CameraPosition {
    require(geometry.positions().any()) { "The geometry contains no positions" }
    return attachmentAuthority
      .awaitAttachment()
      .cameraForGeometry(geometry, bearing, tilt, cameraPadding, fitPadding)
  }

  /**
   * Waits for a viewport, then calculates a camera that fits every position in [coordinates]. See
   * [cameraForGeometry] for the fit, padding, and antimeridian semantics.
   *
   * @throws IllegalArgumentException if [coordinates] is empty.
   * @throws IllegalStateException if the backend cannot calculate a camera for the coordinates.
   */
  public suspend fun cameraForCoordinates(
    coordinates: Collection<Position>,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    cameraPadding: DpPadding? = null,
    fitPadding: DpPadding = DpPadding.Zero,
  ): CameraPosition {
    require(coordinates.isNotEmpty()) { "The coordinates are empty" }
    return cameraForGeometry(
      MultiPoint(coordinates.toList()),
      bearing,
      tilt,
      cameraPadding,
      fitPadding,
    )
  }

  /**
   * Waits for a viewport, then fits [boundingBox] without animation. A newer camera command,
   * accepted input, or detaching cancels this call. See [cameraForBounds] for [fitPadding] and
   * [cameraPadding].
   */
  public suspend fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    cameraPadding: DpPadding? = null,
    fitPadding: DpPadding = DpPadding.Zero,
  ): Unit = coroutineScope {
    val guard = gestureAuthority.beginProgrammatic(currentCoroutineContext()[Job])
    attachmentAuthority
      .awaitAttachment()
      .fitCameraToBounds(boundingBox, bearing, tilt, cameraPadding, fitPadding, guard)
  }

  /**
   * Animates the specified camera properties after a viewport becomes available.
   *
   * On native platforms, omitted properties keep their current animation and timing. A newer
   * command replaces only its specified properties. Flight paths also own target and zoom together.
   * On the browser, a new command stops the previous animation; omitted properties retain their
   * current values. Viewport-inset changes can also stop browser animations.
   *
   * Returns when this command finishes or all its properties have been superseded. Other commands
   * may still be moving. Cancelling the coroutine stops waiting, but an already-started animation
   * continues. Use [stopCameraMovement] to stop all motion. Accepted input, full camera assignment,
   * or attachment loss cancels the call.
   *
   * Android's animator duration scale multiplies the duration; zero applies the update immediately.
   */
  public suspend fun animateCamera(
    update: CameraUpdate,
    animation: CameraAnimation = CameraAnimation.Ease(),
  ): Unit = coroutineScope {
    val guard =
      gestureAuthority.beginProgrammatic(currentCoroutineContext()[Job], concurrent = true)
    attachmentAuthority
      .awaitAttachment()
      .animateCamera(update, animation.scaledBy(systemAnimatorDurationScale()), guard)
  }

  /**
   * Changes zoom, bearing, or tilt while keeping [anchor] at its screen location at animation
   * start. Null camera components are omitted and can animate independently on native platforms.
   * The camera target moves to preserve the anchor; this operation does not accept a destination
   * target or a flight animation.
   *
   * Waits for an attached viewport. The anchor must resolve to a visible point on the map, or this
   * call throws [IllegalArgumentException]. Camera padding and viewport insets both affect the
   * anchor's screen location. This command leaves padding unspecified, so native padding animations
   * can continue. Screen coordinates are relative to the full map, not its padded area.
   *
   * An overlapping native command or any browser command supersedes this move. Accepted input, a
   * logical viewport resize, changed viewport insets, or attachment loss cancels this call.
   * Coroutine cancellation stops waiting; use [stopCameraMovement] to stop motion. Until selective
   * cancellation is available, anchor geometry changes stop all camera animations.
   *
   * Anchor preservation applies to flat Mercator maps, including tilted cameras. Camera constraints
   * take precedence and can move the anchor. Globe and terrain do not have this guarantee. On
   * Android, the system animator duration scale multiplies the duration. Zero duration applies the
   * anchored endpoint immediately.
   */
  public suspend fun animateCameraAround(
    anchor: CameraAnchor,
    zoom: Double? = null,
    bearing: Double? = null,
    tilt: Double? = null,
    animation: CameraAnimation.Ease = CameraAnimation.Ease(),
  ): Unit = coroutineScope {
    require(zoom == null || zoom.isFinite()) { "Zoom must be finite" }
    require(bearing == null || bearing.isFinite()) { "Bearing must be finite" }
    require(tilt == null || tilt.isFinite()) { "Tilt must be finite" }
    require(animation.duration.isFinite() && animation.duration >= Duration.ZERO) {
      "Duration must be finite and nonnegative"
    }
    val guard =
      gestureAuthority.beginProgrammatic(currentCoroutineContext()[Job], concurrent = true)
    attachmentAuthority
      .awaitAttachment()
      .animateCameraAround(
        anchor,
        zoom,
        bearing,
        tilt,
        animation.copy(duration = animation.duration.scaledBy(systemAnimatorDurationScale())),
        guard,
      )
  }

  /**
   * Waits for a viewport, then moves the camera to fit [boundingBox] with [animation]. A newer
   * full-camera assignment or accepted input cancels this call. Further partial updates follow
   * [animateCamera]'s replacement and coroutine-cancellation behavior. See [cameraForBounds] for
   * [fitPadding] and [cameraPadding]. Detaching cancels the call.
   *
   * On Android, the system animator duration scale multiplies the duration of [animation]. A scale
   * of zero jumps to fit [boundingBox].
   */
  public suspend fun animateCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    cameraPadding: DpPadding? = null,
    fitPadding: DpPadding = DpPadding.Zero,
    animation: CameraAnimation = CameraAnimation.Fly(),
  ): Unit = coroutineScope {
    val guard = gestureAuthority.beginProgrammatic(currentCoroutineContext()[Job])
    attachmentAuthority
      .awaitAttachment()
      .animateCameraToBounds(
        boundingBox,
        bearing,
        tilt,
        cameraPadding,
        fitPadding,
        animation.scaledBy(systemAnimatorDurationScale()),
        guard,
      )
  }

  /**
   * Pans the map by [delta] in logical pixels, as a gesture would. A positive x moves the content
   * right.
   *
   * [panBy], [scaleBy], [fling], and [click] pass gestures that your code recognized. They follow
   * the camera permissions and callbacks in [org.maplibre.compose.interaction.MapInteractions],
   * interrupt a camera animation in progress, and report [CameraMoveReason.GESTURE]. They do
   * nothing while no map is presented.
   */
  public fun panBy(delta: DpOffset) {
    recognizedInput?.pan(delta)
  }

  /**
   * Scales the map by [factor], as a gesture would, keeping [anchor] fixed on screen. A null anchor
   * scales about the viewport center. See [panBy].
   */
  public fun scaleBy(factor: Double, anchor: DpOffset? = null) {
    recognizedInput?.scale(factor, anchor)
  }

  /**
   * Continues a pan at [velocity], in logical pixels per second, until the momentum settles or
   * newer input interrupts it. Velocities below the momentum threshold are ignored. See [panBy].
   */
  public fun fling(velocity: DpOffset) {
    recognizedInput?.fling(velocity)
  }

  /**
   * Dispatches a click at [offset], in logical pixels, to the click callbacks and interactive
   * layers. See [panBy].
   */
  public fun click(offset: DpOffset) {
    recognizedInput?.click(offset)
  }

  /** Returns the visible region, or null while no viewport is available. */
  public fun getVisibleRegion(): VisibleRegion? =
    withAttachmentRead(MapAttachment::getVisibleRegion)

  /** Returns the visible axis-aligned bounds, or null while no viewport is available. */
  public fun getVisibleBounds(): VisibleBounds? =
    withAttachmentRead(MapAttachment::getVisibleBounds)

  /**
   * Projects [position] into a logical-pixel offset, or returns null without a viewport.
   *
   * Longitudes equivalent modulo 360° project onto the world copy nearest the camera target.
   */
  public fun screenLocationFromPosition(position: Position): DpOffset? = withAttachmentRead {
    it.screenLocationFromPosition(position)
  }

  internal fun overlayScreenLocationFromPosition(position: Position): DpOffset? =
    withAttachmentRead {
      it.overlayScreenLocationFromPosition(position)
    }

  /**
   * Unprojects [offset] into a geographic position, or returns null without a viewport.
   *
   * Longitude preserves the visible world copy and may extend past ±180°.
   */
  public fun positionFromScreenLocation(offset: DpOffset): Position? = withAttachmentRead {
    it.positionFromScreenLocation(offset)
  }

  /** Returns the ground distance per dp, or null while no viewport is available. */
  public fun metersPerDpAtLatitude(latitude: Double): Double? = withAttachmentRead {
    it.metersPerDpAtLatitude(latitude)
  }

  /**
   * Waits for a viewport, then queries rendered features at [offset] in front-to-back render order.
   * Detaching the map surface during the query cancels it.
   *
   * A geometry that crosses the antimeridian may come back split into pieces, with longitudes past
   * ±180° in either direction. When several world copies are visible, the same source feature can
   * appear once per copy it occupies in the query area.
   */
  public suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    attachmentAuthority.awaitAttachment().queryRenderedFeatures(offset, layerIds, predicate)

  /**
   * Waits for a viewport, then queries rendered features that intersect [rect] in front-to-back
   * render order. Detaching the map surface during the query cancels it.
   *
   * A geometry that crosses the antimeridian may come back split into pieces, with longitudes past
   * ±180° in either direction. When several world copies are visible, the same source feature can
   * appear once per copy it occupies in the query area.
   */
  public suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    attachmentAuthority.awaitAttachment().queryRenderedFeatures(rect, layerIds, predicate)

  /** Waits for the first viewport from the current or a future map attachment. */
  public suspend fun awaitViewport(): Viewport = attachmentAuthority.awaitViewport()

  internal fun reservePresentation(
    owner: MapPresentationOwnerToken = MapPresentationOwnerToken()
  ): MapPresentationToken = lifecycle.reservePresentation(owner)

  internal fun publishPresentation(
    token: MapPresentationToken,
    adapter: MapAdapter,
  ) = lifecycle.publishPresentation(token, adapter)

  internal fun releasePresentation(token: MapPresentationToken, adapter: MapAdapter? = null) =
    lifecycle.releasePresentation(token, adapter)

  internal fun retainedAdapter(compatibilityKey: Any): MapAdapter? =
    lifecycle.retainedAdapter(compatibilityKey)

  internal fun durableStyleCallbacks(): MapAdapter.Callbacks = DurableStyleCallbacks(this)

  private fun requireOpenLocked() {
    check(!lifecycle.isClosed) { "The map state is closed" }
  }

  private inline fun <T> withAttachmentRead(block: (MapAttachment) -> T?): T? {
    val attachment = currentMapAttachment ?: return null
    return try {
      block(attachment)
    } catch (_: MapAttachmentChangedException) {
      null
    }
  }
}

@JvmInline internal value class MapPresentationToken(val value: Long)

internal class MapPresentationOwnerToken

/**
 * Remembers a logical map and closes it when this call leaves composition.
 *
 * [baseStyle] owns the map's base style and updates it on recomposition. Its
 * [MapStyleState.asMutable] is null. [initialCameraPosition] only seeds the camera; use
 * [MapState.setCameraPosition] to move it. Changes to [content] update the declared resources.
 * Restoration creates a new map with the saved camera position and the current [baseStyle].
 *
 * [content] declares the map's sources, layers, and images. It reads the returned state through
 * [LocalMapState] and its viewport through [LocalViewport].
 */
@Composable
public fun rememberMapState(
  runtime: MapRuntime = DefaultMapRuntime.instance,
  baseStyle: BaseStyle = BaseStyle.Demo,
  initialCameraPosition: CameraPosition = CameraPosition(),
  content: @Composable @MaplibreComposable () -> Unit = {},
): MapState {
  val currentContent by rememberUpdatedState(content)
  val stableContent = remember<@Composable @MaplibreComposable () -> Unit> { { currentContent() } }
  val state =
    rememberSaveable(runtime, saver = mapStateSaver(runtime, baseStyle, stableContent)) {
      runtime
        .createMapState(
          baseStyle = baseStyle,
          cameraPosition = initialCameraPosition,
          content = stableContent,
        )
        .also { it.style.baseStyleDeclared = true }
    }
  SideEffect { state.style.updateBaseStyle(baseStyle) }
  DisposableEffect(state) { onDispose { state.close() } }
  return state
}

private fun mapStateSaver(
  runtime: MapRuntime,
  baseStyle: BaseStyle,
  content: @Composable @MaplibreComposable () -> Unit,
): Saver<MapState, List<Double>> =
  Saver(
    save = { state ->
      with(state.cameraPosition) {
        listOf(
          bearing,
          target.longitude,
          target.latitude,
          tilt,
          zoom,
          padding.left.value.toDouble(),
          padding.top.value.toDouble(),
          padding.right.value.toDouble(),
          padding.bottom.value.toDouble(),
        )
      }
    },
    restore = { values ->
      require(values.size == 9) { "Invalid saved camera position" }
      runtime
        .createMapState(
          baseStyle = baseStyle,
          cameraPosition =
            CameraPosition(
              bearing = values[0],
              target = Position(longitude = values[1], latitude = values[2]),
              tilt = values[3],
              zoom = values[4],
              padding =
                DpPadding(
                  values[5].dp,
                  values[6].dp,
                  values[7].dp,
                  values[8].dp,
                ),
            ),
          content = content,
        )
        .also { it.style.baseStyleDeclared = true }
    },
  )

internal class RuntimeImplementation(
  internal val platformContext: Any?,
  private val closeResources: suspend () -> Unit,
  internal val logger: MapLog?,
  offlineManagerBackend: OfflineManagerBackend = UnsupportedOfflineManager,
  internal val physicalScope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default),
  /** Delivers engine callbacks to map states. Runs them inline when no dispatch is needed. */
  internal val mainDispatcher: CoroutineDispatcher = platformMainDispatcher(),
  /** Runs map-state work that resumes after an engine read. */
  internal val mainScope: CoroutineScope = CoroutineScope(SupervisorJob() + mainDispatcher),
  /** Runs engine reads that block until the map owner thread answers. */
  internal val readDispatcher: CoroutineDispatcher =
    physicalScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
      ?: Dispatchers.Default,
  internal val createSnapshotterAdapter: () -> SnapshotterAdapter = ::unsupportedSnapshots,
  internal val styleEvaluator: StyleCompositionEvaluator = DefaultStyleCompositionEvaluator,
  internal val resourceConfig: MapResourceConfig = MapResourceConfig(),
) : MapRuntime {
  override val offlineManager: OfflineManager =
    RuntimeBoundOfflineManager(
      delegate = offlineManagerBackend,
      requireRuntimeOpen = ::requireOpen,
    )
  private val lock = reentrantLock()
  private val children = linkedSetOf<MapState>()
  private val snapshotters = linkedSetOf<MapSnapshotterImplementation>()
  private val closure = CompletableDeferred<Result<Unit>>()
  private var closed = false
  private var closedState: Boolean by mutableStateOf(false)

  final override fun createMapState(
    baseStyle: BaseStyle,
    cameraPosition: CameraPosition,
    content: @Composable @MaplibreComposable () -> Unit,
  ): MapState = lock.withLock {
    requireOpenLocked()
    MapState(this, cameraPosition, baseStyle, content).also(children::add)
  }

  final override fun createSnapshotter(
    baseStyle: BaseStyle,
    content: @Composable @MaplibreComposable () -> Unit,
  ): MapSnapshotter = lock.withLock {
    requireOpenLocked()
    MapSnapshotterImplementation(this, baseStyle, content).also(snapshotters::add)
  }

  private fun requireOpen() {
    lock.withLock { requireOpenLocked() }
  }

  private fun requireOpenLocked() {
    check(!closed) { "The map runtime is closed" }
  }

  override fun close() {
    val closingChildren = lock.withLock {
      if (closed) return
      closed = true
      Snapshot.withMutableSnapshot { closedState = true }
      children.toList() to snapshotters.toList()
    }
    val (closingStates, closingSnapshotters) = closingChildren
    closingStates.forEach(MapState::close)
    closingSnapshotters.forEach(MapSnapshotterImplementation::close)
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val failures = mutableListOf<Throwable>()
      closingStates.forEach { child ->
        runCatching { child.awaitClosed() }.exceptionOrNull()?.let(failures::addCleanupFailure)
      }
      closingSnapshotters.forEach { child ->
        runCatching { child.awaitClosed() }.exceptionOrNull()?.let(failures::addCleanupFailure)
      }
      runCatching { closeResources() }.exceptionOrNull()?.let(failures::addCleanupFailure)
      mainScope.cancel()
      closure.complete(failures.cleanupResult("Map runtime"))
    }
  }

  override val isClosed: Boolean
    get() = closedState

  override suspend fun awaitClosed() {
    closure.await().getOrThrow()
  }

  internal fun childClosed(child: MapState) {
    lock.withLock { children.remove(child) }
  }

  internal fun childClosed(child: MapSnapshotterImplementation) {
    lock.withLock { snapshotters.remove(child) }
  }
}
