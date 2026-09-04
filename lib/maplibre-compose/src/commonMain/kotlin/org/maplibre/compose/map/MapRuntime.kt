@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.layers.LayerHandle
import org.maplibre.compose.layers.layerHandle
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.offline.OfflineManager
import org.maplibre.compose.offline.RuntimeBoundOfflineManager
import org.maplibre.compose.offline.UnsupportedOfflineManager
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceHandle
import org.maplibre.compose.sources.sourceHandle
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.Light
import org.maplibre.compose.style.Projection
import org.maplibre.compose.style.Sky
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.canUpdateTo
import org.maplibre.compose.util.ImageStretch
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/**
 * Configuration for one [MapRuntime].
 *
 * Every platform accepts a request interceptor and a resource provider. The MapLibre Native
 * platforms also accept a cache file and a cache size limit.
 */
public expect class MapRuntimeOptions

/** Creates a runtime from [options]. The caller must close the result. */
public expect fun createMapRuntime(options: MapRuntimeOptions): MapRuntime

/**
 * Returns the default runtime for this process. Closing this runtime permanently closes the process
 * default; later calls return the same closed runtime.
 */
@Composable public expect fun rememberDefaultMapRuntime(): MapRuntime

/** Creates logical maps that share one application-level configuration. */
public interface MapRuntime {
  /** The offline packs and ambient cache managed by this runtime. */
  public val offlineManager: OfflineManager

  /**
   * Replaces the request interceptor for every map and snapshotter on this runtime.
   *
   * A null [interceptor] stops rewriting URLs and adding headers. A request that starts after this
   * call returns uses the new interceptor. A request in flight may use either interceptor.
   */
  public fun setRequestInterceptor(interceptor: MapRequestInterceptor?)

  /**
   * Creates a logical map with [baseStyle] and the sources, layers, and images that [content]
   * declares. The caller must close the result.
   *
   * [content] reads the returned state through [LocalMapState] and its viewport through
   * [LocalViewport].
   */
  public fun createMapState(
    baseStyle: BaseStyle,
    initialCameraPosition: CameraPosition = CameraPosition(),
    content: @Composable @MaplibreComposable () -> Unit = {},
  ): MapState

  /**
   * Creates an independent non-UI map with [baseStyle] and the sources, layers, and images that
   * [content] declares, for image capture. The caller must close the result.
   *
   * [content] reads the viewport of each capture request through [LocalViewport]. It has no
   * [MapState], so reading [LocalMapState] throws.
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

  /** Indicates that the current map surface loaded the desired style. */
  public data object Ready : StyleLoadState

  /** Indicates that the current map surface failed to load the desired style. */
  public data class Failed(public val reason: String?) : StyleLoadState
}

internal interface MapStyleStateOwner {
  fun setBaseStyle(value: BaseStyle)

  fun desiredSourceDefinition(id: String): org.maplibre.compose.style.SourceDefinition?

  fun addStyleSource(source: Source): SourceHandle

  fun removeStyleSource(id: String): Boolean

  fun addStyleImage(id: String, image: ImageBitmap, sdf: Boolean, stretch: ImageStretch?)

  fun removeStyleImage(id: String): Boolean

  fun readyLoadedStyle(): StyleBinding?

  fun <T> runStyleHandleOperation(binding: StyleBinding, action: () -> T): T

  fun styleHandleCheckpoint(binding: StyleBinding): Long

  fun requireStyleHandleUnchanged(binding: StyleBinding, checkpoint: Long)
}

/** Desired and applied style state for one logical map or snapshotter. */
public class MapStyleState internal constructor(initialBaseStyle: BaseStyle) {
  private var owner: MapStyleStateOwner? = null
  private val loadedStyle = AtomicReference<StyleBinding?>(null)
  private val sourceIdentities = AtomicReference<Map<String, StyleResourceIdentity>>(emptyMap())
  private val layerIdentities = AtomicReference<Map<String, StyleResourceIdentity>>(emptyMap())
  private var sourcesState: Map<String, SourceHandle> by mutableStateOf(emptyMap())
  private var layersState: Map<String, LayerHandle> by mutableStateOf(emptyMap())
  private var baseStyleState: BaseStyle by
    mutableStateOf(initialBaseStyle, structuralEqualityPolicy())

  public var baseStyle: BaseStyle
    get() = baseStyleState
    set(value) {
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

  /** Light of the current loaded-style generation. */
  public val light: StyleLight = StyleLight(this)

  /** Sky of the current loaded-style generation. */
  public val sky: StyleSky = StyleSky(this)

  /** Projection of the current loaded-style generation. */
  public val projection: StyleProjection = StyleProjection(this)

  internal fun transitionOptions(): TransitionOptions? = readStyle { it.transition() }

  internal fun setTransitionOptions(options: TransitionOptions) {
    mutateStyle("the transition") { it.setTransition(options) }
  }

  internal fun placementTransitions(): Boolean? = readStyle { it.placementTransitions() }

  internal fun setPlacementTransitions(enabled: Boolean) {
    mutateStyle("placement transitions") { it.setPlacementTransitions(enabled) }
  }

  internal fun lightProperty(name: String): JsonElement? = readStyle { it.lightProperty(name) }

  internal fun setLight(light: Light) {
    mutateStyle("the light") { it.setLight(light.toJson()) }
  }

  internal fun skyProperty(name: String): JsonElement? = readStyle { it.skyProperty(name) }

  internal fun setSky(sky: Sky?) {
    mutateStyle("the sky") { it.setSky(sky?.toJson()) }
  }

  internal fun projectionProperty(name: String): JsonElement? = readStyle {
    it.projectionProperty(name)
  }

  internal fun setProjection(projection: Projection) {
    mutateStyle("the projection") { it.setProjection(projection.toJson()) }
  }

  private fun <T> readStyle(read: (StyleBinding) -> T?): T? {
    val current = readyLoadedStyle() ?: return null
    return operationGuard(current).run { read(current) }
  }

  private fun mutateStyle(what: String, mutate: (StyleBinding) -> Unit) {
    val current = checkNotNull(readyLoadedStyle()) { "No ready loaded style" }
    operationGuard(current).run {
      try {
        mutate(current)
      } catch (error: StyleMutationException) {
        throw StyleHandleException("Could not set $what: ${error.message}", error)
      }
    }
  }

  internal fun sourceHandle(id: String): SourceHandle? {
    if (readyLoadedStyle() == null) return null
    return sourcesState[id]
  }

  private fun sourceHandle(current: StyleBinding, id: String): SourceHandle? = owner.let { owner ->
    val definition = owner?.desiredSourceDefinition(id)
    val identity = sourceIdentity(id)
    current.sourceHandle(
      id = id,
      definition = definition,
      currentDefinition = { owner?.desiredSourceDefinition(id) },
      isCurrentResource = { sourceIdentities.load()[id] === identity },
      operations = operationGuard(current),
    )
  }

  internal fun layerHandle(id: String): LayerHandle? {
    if (readyLoadedStyle() == null) return null
    return layersState[id]
  }

  private fun readyLoadedStyle(): StyleBinding? {
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
    sourceIdentities.store(emptyMap())
    layerIdentities.store(emptyMap())
    sourcesState = emptyMap()
    layersState = emptyMap()
  }

  internal fun invalidateLoadedStyle() {
    loadedStyle.exchange(null)?.invalidate()
    sourceIdentities.store(emptyMap())
    layerIdentities.store(emptyMap())
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

  internal fun readSources(current: StyleBinding): Map<String, SourceHandle> {
    val ids = current.getSources().mapTo(linkedSetOf()) { it.id }
    retainResourceIdentities(sourceIdentities, ids)
    return ids.mapNotNull { id -> sourceHandle(current, id)?.let { id to it } }.toMap()
  }

  internal fun updateSources(sources: Map<String, SourceHandle>) {
    sourcesState = sources
  }

  internal fun readLayers(current: StyleBinding): Map<String, LayerHandle> {
    val ids = current.getLayers().mapTo(linkedSetOf()) { it.id }
    retainResourceIdentities(layerIdentities, ids)
    return ids
      .mapNotNull { id ->
        val identity = layerIdentity(id)
        current
          .layerHandle(
            id,
            isCurrentResource = { layerIdentities.load()[id] === identity },
            operations = operationGuard(current),
          )
          ?.let { id to it }
      }
      .toMap()
  }

  internal fun invalidateSourceIdentities(ids: Set<String>) {
    removeResourceIdentities(sourceIdentities, ids)
  }

  internal fun invalidateLayerIdentities(ids: Set<String>) {
    removeResourceIdentities(layerIdentities, ids)
  }

  internal fun invalidateStructurallyReplacedResources(
    previous: DesiredStyleRevision,
    next: DesiredStyleRevision,
  ) {
    val nextSources = next.sources.associateBy(SourceDefinition::id)
    val replacedSourceIds =
      previous.sources
        .filter { previousSource ->
          nextSources[previousSource.id]?.let(previousSource::canUpdateTo) != true
        }
        .mapTo(mutableSetOf(), SourceDefinition::id)
    invalidateSourceIdentities(replacedSourceIds)

    val nextLayers = next.layers.associateBy { it.definition.id }
    val replacedLayerIds =
      previous.layers
        .filter { previousLayer ->
          val nextLayer = nextLayers[previousLayer.definition.id]
          nextLayer == null ||
            nextLayer.anchor != previousLayer.anchor ||
            nextLayer.definition.type != previousLayer.definition.type ||
            nextLayer.definition.sourceId != previousLayer.definition.sourceId ||
            nextLayer.definition.value["source-layer"] !=
              previousLayer.definition.value["source-layer"] ||
            previousLayer.definition.sourceId in replacedSourceIds
        }
        .mapTo(mutableSetOf()) { it.definition.id }
    invalidateLayerIdentities(replacedLayerIds)
  }

  internal fun updateResources(resources: LoadedStyleResources) {
    sourcesState = resources.sources
    layersState = resources.layers
  }

  internal fun sourceHandles(): Map<String, SourceHandle> =
    if (readyLoadedStyle() == null) emptyMap() else sourcesState

  internal fun layerHandles(): Map<String, LayerHandle> =
    if (readyLoadedStyle() == null) emptyMap() else layersState

  private fun operationGuard(style: StyleBinding): StyleHandleOperationGuard =
    object : StyleHandleOperationGuard {
      override fun <T> run(action: () -> T): T =
        owner?.runStyleHandleOperation(style, action) ?: action()

      override fun checkpoint(): Long = owner?.styleHandleCheckpoint(style) ?: 0L

      override fun requireUnchanged(checkpoint: Long) {
        owner?.requireStyleHandleUnchanged(style, checkpoint)
      }
    }

  private fun sourceIdentity(id: String): StyleResourceIdentity =
    resourceIdentity(sourceIdentities, id)

  private fun layerIdentity(id: String): StyleResourceIdentity =
    resourceIdentity(layerIdentities, id)
}

private class StyleResourceIdentity

private fun resourceIdentity(
  identities: AtomicReference<Map<String, StyleResourceIdentity>>,
  id: String,
): StyleResourceIdentity {
  while (true) {
    val current = identities.load()
    current[id]?.let {
      return it
    }
    val identity = StyleResourceIdentity()
    if (identities.compareAndSet(current, current + (id to identity))) return identity
  }
}

private fun retainResourceIdentities(
  identities: AtomicReference<Map<String, StyleResourceIdentity>>,
  ids: Set<String>,
) {
  while (true) {
    val current = identities.load()
    val retained = current.filterKeys { it in ids }
    if (retained.size == current.size || identities.compareAndSet(current, retained)) return
  }
}

private fun removeResourceIdentities(
  identities: AtomicReference<Map<String, StyleResourceIdentity>>,
  ids: Set<String>,
) {
  if (ids.isEmpty()) return
  while (true) {
    val current = identities.load()
    val remaining = current - ids
    if (remaining.size == current.size || identities.compareAndSet(current, remaining)) return
  }
}

internal data class LoadedStyleResources(
  val sources: Map<String, SourceHandle>,
  val layers: Map<String, LayerHandle>,
)

internal class ImperativeSourceRecord(val definition: SourceDefinition)

internal class ImperativeImageRecord

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
  private val owner: MapState,
  internal val token: MapPresentationToken,
  internal val adapter: MapAdapter,
) {
  private val invalidated = CompletableDeferred<Unit>()
  private var validState: Boolean by mutableStateOf(true)
  private var viewportState: Viewport? by mutableStateOf(null)
  private val firstViewport = CompletableDeferred<Viewport>()
  private var gestureActiveState: Boolean by mutableStateOf(false)
  private var cameraChangingState: Boolean by mutableStateOf(false)
  private var moveReasonState: CameraMoveReason by mutableStateOf(CameraMoveReason.NONE)
  val isValid: Boolean
    get() = validState

  val viewport: Viewport?
    get() = viewportState

  val isCameraMoving: Boolean
    get() = gestureActiveState || cameraChangingState

  val cameraMoveReason: CameraMoveReason
    get() = moveReasonState

  suspend fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
  ): Unit = runLeaseBound {
    awaitViewportState()
    adapter.fitCameraToBounds(boundingBox, bearing, tilt, padding)
  }

  suspend fun animateCameraPosition(
    position: CameraPosition,
    duration: Duration = 300.milliseconds,
  ): Unit = runLeaseBound { adapter.animateCameraPosition(position, duration) }

  suspend fun animateCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
    duration: Duration = 300.milliseconds,
  ): Unit = runLeaseBound {
    awaitViewportState()
    adapter.animateCameraToBounds(boundingBox, bearing, tilt, padding, duration)
  }

  fun getVisibleRegion(): VisibleRegion? = withViewport { it.getVisibleRegion() }

  fun getVisibleBoundingBox(): BoundingBox? = withViewport { it.getVisibleBoundingBox() }

  fun screenLocationFromPosition(position: Position): DpOffset? = withViewport {
    it.screenLocationFromPosition(position)
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

  suspend fun awaitViewport(): Viewport = runLeaseBound { awaitViewportState() }

  internal fun updateViewport(value: Viewport?) {
    owner.lifecycle.serialized {
      viewportState = value
      value?.let(firstViewport::complete)
    }
  }

  /**
   * A gesture sets the reason even while an engine camera change is in flight, because the gesture
   * token decides whether a change belongs to the user.
   */
  internal fun setGestureActive(active: Boolean) {
    owner.lifecycle.serialized {
      gestureActiveState = active
      if (active) moveReasonState = CameraMoveReason.GESTURE
    }
  }

  internal fun cameraChangeStarted() {
    owner.lifecycle.serialized {
      cameraChangingState = true
      if (!gestureActiveState) moveReasonState = CameraMoveReason.PROGRAMMATIC
    }
  }

  internal fun cameraChangeEnded() {
    owner.lifecycle.serialized { cameraChangingState = false }
  }

  internal fun invalidate() {
    owner.lifecycle.serialized {
      validState = false
      viewportState = null
      gestureActiveState = false
      cameraChangingState = false
      invalidated.complete(Unit)
    }
  }

  private fun Expression<BooleanValue>.compileOrNull(): CompiledExpression<BooleanValue>? {
    if (this == const(true)) return null
    return compile(ExpressionContext.None)
  }

  private fun <T> withViewport(block: (MapAdapter) -> T): T? =
    owner.withCurrentOrNull(this) { if (viewportState == null) null else block(adapter) }

  private suspend fun awaitViewportState(): Viewport = firstViewport.await()

  private suspend fun <T> runLeaseBound(block: suspend () -> T): T = coroutineScope {
    if (!owner.isCurrent(this@MapAttachment)) throw MapAttachmentChangedException()
    val operation =
      async(start = CoroutineStart.UNDISPATCHED) {
        if (!owner.isCurrent(this@MapAttachment)) throw MapAttachmentChangedException()
        block()
      }
    select {
      operation.onAwait { it }
      invalidated.onAwait {
        operation.cancelAndJoin()
        throw MapAttachmentChangedException()
      }
    }
  }
}

private class MapAttachmentChangedException :
  CancellationException("The map attachment changed during the operation")

/** Holds the observable style, camera, and map operations for one logical map. */
@Stable
public class MapState
internal constructor(
  internal val runtime: RuntimeImplementation,
  initialCameraPosition: CameraPosition,
  initialBaseStyle: BaseStyle,
  content: @Composable @MaplibreComposable () -> Unit,
) {
  internal val styleContent: @Composable @MaplibreComposable () -> Unit = {
    CompositionLocalProvider(LocalMapState provides this, LocalViewport provides viewport) {
      content()
    }
  }
  internal val lifecycle = MapLifecycleAuthority(this, runtime.physicalScope)
  private var baseStyleCommandRevision = 0L
  private var cameraCommandRevision = 0L
  private var styleHandleEpoch = 0L
  private var styleSourceChangeRevision = 0L
  private val imperativeSources = mutableMapOf<String, ImperativeSourceRecord>()
  private val imperativeImages = mutableMapOf<String, ImperativeImageRecord>()
  private var missingImageResolverState: MissingImageResolver? by mutableStateOf(null)
  /** Resolutions started for the loaded style, by image id. */
  private val missingImageResolutions = mutableMapOf<String, MissingImageResolution>()
  private var activeStyleMutation: StyleMutationReservation? = null
  /**
   * Held while a resolved missing image reaches the style. A style command the app issues cannot
   * anticipate this one, so the command waits it out through [beginStyleRevision] or runs beside it
   * rather than failing on it.
   */
  private var backgroundStyleMutation: StyleMutationReservation? = null
  private val eventsFlow =
    MutableSharedFlow<MapEvent>(
      extraBufferCapacity = 64,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
  private var closedState: Boolean by mutableStateOf(false)
  private var cameraPositionState: CameraPosition by
    mutableStateOf(initialCameraPosition, structuralEqualityPolicy())

  internal var desiredStyleRevision: DesiredStyleRevision = DesiredStyleRevision.Empty

  public val style: MapStyleState =
    MapStyleState(initialBaseStyle).also {
      it.attach(
        object : MapStyleStateOwner {
          override fun setBaseStyle(value: BaseStyle) = this@MapState.setBaseStyle(value)

          override fun desiredSourceDefinition(id: String) =
            this@MapState.desiredSourceDefinition(id)

          override fun addStyleSource(source: Source) = this@MapState.addStyleSource(source)

          override fun removeStyleSource(id: String) = this@MapState.removeStyleSource(id)

          override fun addStyleImage(
            id: String,
            image: ImageBitmap,
            sdf: Boolean,
            stretch: ImageStretch?,
          ) = this@MapState.addStyleImage(id, image, sdf, stretch)

          override fun removeStyleImage(id: String) = this@MapState.removeStyleImage(id)

          override fun readyLoadedStyle() = this@MapState.readyLoadedStyle()

          override fun <T> runStyleHandleOperation(
            binding: StyleBinding,
            action: () -> T,
          ): T = this@MapState.runStyleHandleOperation(binding, action)

          override fun styleHandleCheckpoint(binding: StyleBinding) =
            this@MapState.styleHandleCheckpoint(binding)

          override fun requireStyleHandleUnchanged(
            binding: StyleBinding,
            checkpoint: Long,
          ) = this@MapState.requireStyleHandleUnchanged(binding, checkpoint)
        }
      )
    }

  public val cameraPosition: CameraPosition
    get() = cameraPositionState

  internal var currentMapAttachment: MapAttachment? by mutableStateOf(null)
    internal set

  private var nextMapAttachment = CompletableDeferred<MapAttachment>()
  private val cameraMutation = MutatorMutex()

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
   * Emits each [MapEvent] that the engine behind this map reports.
   *
   * A collector receives the events that the map reports after it subscribes. The flow replays
   * nothing, and a bounded buffer drops the oldest event that a collector has not taken. Style and
   * idle events continue while a retained native engine stays alive between presentations, and
   * camera and frame events stop while no map surface is attached.
   *
   * A collector on an undispatched context runs on the thread that reported the event, which is the
   * map's own thread on native platforms and the MapLibre GL JS event listener on the browser, and
   * it runs while the map holds the lock that serializes its lifecycle. Read state and record
   * values there. Collect on a dispatcher to call a map command such as [StyleImages.add].
   */
  public val events: Flow<MapEvent> = eventsFlow.asSharedFlow()

  /**
   * Supplies images that the style draws and the loaded style does not hold, such as an icon that
   * its sprite does not contain.
   *
   * The map calls the resolver with the image id and adds the [ResolvedStyleImage] that it returns
   * to the loaded style. A resolver that returns null leaves the image unresolved, as does one that
   * throws, which the map logs. The map calls the resolver at most once per image id per loaded
   * style. A new base style, or a different resolver set here, lets the next engine request for
   * that id reach the resolver again.
   *
   * The map never calls the resolver inline from the engine's callback, and the resolver may
   * suspend. MapLibre GL JS waits for it before it draws without the image; MapLibre Native draws
   * the image at the next symbol placement after the resolver answers. Setting a resolver here does
   * not stop a resolution that is already in flight, which still supplies the image that it
   * resolves.
   *
   * Set it before or after the style loads. Null, the default, leaves every missing image
   * unresolved.
   */
  public var missingImageResolver: MissingImageResolver?
    get() = missingImageResolverState
    set(value) {
      lifecycle.serialized {
        if (missingImageResolverState === value) return
        missingImageResolverState = value
        // Forgotten rather than cancelled: the request that started a resolution in flight is still
        // outstanding, and the engine waits on that resolution rather than asking the new resolver.
        missingImageResolutions.clear()
      }
    }

  public val isClosed: Boolean
    get() = closedState

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
  public fun setCameraPosition(position: CameraPosition) {
    val command = lifecycle.serialized {
      requireOpenLocked()
      cameraPositionState = position
      cameraCommandRevision++
      val attachment = currentMapAttachment ?: return
      AttachmentCameraCommand(
        attachment = attachment,
        command = CameraCommand(attachment.adapter, position, cameraCommandRevision),
      )
    }
    applyAttachmentCameraCommand(command.attachment, command.command)
  }

  /** Waits for a viewport, then fits [boundingBox] without animation. */
  public suspend fun fitCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
  ): Unit = retryAcrossAttachments {
    it.fitCameraToBounds(boundingBox, bearing, tilt, padding)
  }

  /** Waits for an attached map, then animates to [position]. A new animation replaces this one. */
  public suspend fun animateCameraPosition(
    position: CameraPosition,
    duration: Duration = 300.milliseconds,
  ): Unit = cameraMutation.mutate {
    retryAcrossAttachments { it.animateCameraPosition(position, duration) }
  }

  /**
   * Waits for a viewport, then animates to fit [boundingBox]. A new animation replaces this one.
   */
  public suspend fun animateCameraToBounds(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
    duration: Duration = 300.milliseconds,
  ): Unit = cameraMutation.mutate {
    retryAcrossAttachments {
      it.animateCameraToBounds(boundingBox, bearing, tilt, padding, duration)
    }
  }

  /** Returns the visible region, or null while no viewport is available. */
  public fun getVisibleRegion(): VisibleRegion? =
    withAttachmentRead(MapAttachment::getVisibleRegion)

  /** Returns the visible axis-aligned bounds, or null while no viewport is available. */
  public fun getVisibleBoundingBox(): BoundingBox? =
    withAttachmentRead(MapAttachment::getVisibleBoundingBox)

  /** Projects [position] into a logical-pixel offset, or returns null without a viewport. */
  public fun screenLocationFromPosition(position: Position): DpOffset? = withAttachmentRead {
    it.screenLocationFromPosition(position)
  }

  /** Unprojects [offset] into a geographic position, or returns null without a viewport. */
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
   */
  public suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    awaitAttachment().queryRenderedFeatures(offset, layerIds, predicate)

  /**
   * Waits for a viewport, then queries rendered features that intersect [rect] in front-to-back
   * render order. Detaching the map surface during the query cancels it.
   */
  public suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    awaitAttachment().queryRenderedFeatures(rect, layerIds, predicate)

  /** Waits for the first viewport from the current or a future map attachment. */
  public suspend fun awaitViewport(): Viewport =
    retryAcrossAttachments(MapAttachment::awaitViewport)

  private suspend fun <T> retryAcrossAttachments(operation: suspend (MapAttachment) -> T): T {
    var attachment = awaitAttachment()
    while (true) {
      try {
        return operation(attachment)
      } catch (_: MapAttachmentChangedException) {
        attachment = awaitReplacementAttachment()
      }
    }
  }

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

  internal fun markStyleReady(adapter: MapAdapter): Boolean {
    while (true) {
      val read = lifecycle.serialized {
        if (!lifecycle.acceptsAdapter(adapter)) return false
        val binding = style.currentLoadedStyle() ?: return false
        StyleResourceRead(binding, styleHandleEpoch, styleSourceChangeRevision)
      }
      val resources = runCatching { style.readResources(read.binding) }
      if (resources.isFailure) {
        val stillCurrent = lifecycle.serialized { isCurrentStyleResourceRead(adapter, read) }
        if (!stillCurrent) return false
        throw requireNotNull(resources.exceptionOrNull())
      }
      val committed = lifecycle.serialized {
        if (!isCurrentStyleResourceRead(adapter, read)) return false
        if (style.loadState is StyleLoadState.Failed) return false
        if (styleSourceChangeRevision != read.sourceChangeRevision) return@serialized false
        style.updateResources(resources.getOrThrow())
        style.loadState = StyleLoadState.Ready
        true
      }
      if (committed) return true
    }
  }

  internal fun acceptsPresentationEvent(adapter: MapAdapter): Boolean =
    lifecycle.acceptsPresentation(adapter)

  internal fun refreshStyleSources(adapter: MapAdapter): Boolean {
    val read = lifecycle.serialized {
      if (!lifecycle.acceptsAdapter(adapter)) return false
      val sourceChangeRevision = ++styleSourceChangeRevision
      if (style.loadState != StyleLoadState.Ready) return true
      val binding = style.currentLoadedStyle() ?: return true
      StyleResourceRead(binding, styleHandleEpoch, sourceChangeRevision)
    }
    val sources = runCatching { style.readSources(read.binding) }
    if (sources.isFailure) {
      val stillCurrent = lifecycle.serialized { isCurrentStyleResourceRead(adapter, read) }
      if (!stillCurrent) return false
      throw requireNotNull(sources.exceptionOrNull())
    }
    return lifecycle.serialized {
      if (!isCurrentStyleResourceRead(adapter, read)) return false
      if (style.loadState != StyleLoadState.Ready) return false
      // A later callback performs its own complete read, preserving source order without allowing
      // this older result to overwrite it.
      if (styleSourceChangeRevision != read.sourceChangeRevision) return true
      style.updateSources(sources.getOrThrow())
      true
    }
  }

  private fun isCurrentStyleResourceRead(adapter: MapAdapter, read: StyleResourceRead): Boolean =
    lifecycle.acceptsAdapter(adapter) &&
      styleHandleEpoch == read.styleHandleEpoch &&
      style.currentLoadedStyle() === read.binding

  internal fun updateLoadedStyle(adapter: MapAdapter, loadedStyle: StyleBinding?): Boolean =
    lifecycle.serialized {
      if (!lifecycle.acceptsAdapter(adapter)) return false
      if (style.currentLoadedStyle() === loadedStyle) return true
      styleHandleEpoch++
      imperativeSources.clear()
      imperativeImages.clear()
      cancelMissingImageResolutions()
      style.loadState = StyleLoadState.Loading
      style.updateLoadedStyle(loadedStyle)
      true
    }

  internal fun readyLoadedStyle(): StyleBinding? = lifecycle.serialized {
    style.currentLoadedStyle()?.takeIf { style.loadState == StyleLoadState.Ready }
  }

  internal fun markStyleFailed(adapter: MapAdapter, reason: String?) {
    lifecycle.serialized {
      if (lifecycle.acceptsAdapter(adapter)) {
        style.loadState = StyleLoadState.Failed(reason)
      }
    }
  }

  internal suspend fun beginStyleRevision(adapter: MapAdapter, revision: DesiredStyleRevision) {
    while (true) {
      val mutation = lifecycle.serialized {
        if (!lifecycle.acceptsAdapter(adapter)) return
        activeStyleMutation
          ?: backgroundStyleMutation
          ?: run {
            requireNoImperativeResourceConflicts(revision)
            style.invalidateStructurallyReplacedResources(desiredStyleRevision, revision)
            styleHandleEpoch++
            desiredStyleRevision = revision
            style.loadState = StyleLoadState.Loading
            return
          }
      }
      mutation.completion.await()
    }
  }

  internal fun setBaseStyle(value: BaseStyle) {
    val command = lifecycle.serialized {
      requireOpenLocked()
      if (style.baseStyle == value) return
      requireNoActiveStyleMutation()
      styleHandleEpoch++
      imperativeSources.clear()
      imperativeImages.clear()
      cancelMissingImageResolutions()
      style.setBaseStyleState(value)
      style.invalidateLoadedStyle()
      val adapter = lifecycle.currentAdapter()
      if (adapter == null) {
        style.loadState = StyleLoadState.Pending
        baseStyleCommandRevision++
        return
      } else {
        style.loadState = StyleLoadState.Loading
      }
      BaseStyleCommand(adapter, value, ++baseStyleCommandRevision)
    }
    applyBaseStyleCommand(command)
  }

  internal fun desiredSourceDefinition(id: String): org.maplibre.compose.style.SourceDefinition? =
    lifecycle.serialized {
      desiredStyleRevision.sources.firstOrNull { it.id == id } ?: imperativeSources[id]?.definition
    }

  internal fun addStyleSource(source: Source): SourceHandle {
    val definition = source.definition()
    val record = ImperativeSourceRecord(definition)
    val reservation = StyleMutationReservation()
    val binding = lifecycle.serialized {
      requireOpenLocked()
      requireNoDesiredSource(source.id)
      requireNoActiveStyleMutation()
      if (source.id in imperativeSources) {
        throw StyleHandleException("Source ID '${source.id}' already exists in style")
      }
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked).also {
        imperativeSources[source.id] = record
        activeStyleMutation = reservation
      }
    }
    var committed = false
    try {
      if (binding.sourceExists(source.id) == true) {
        throw StyleHandleException("Source ID '${source.id}' already exists in style")
      }
      val added = binding.addSource(definition)
      if (!added) throw IllegalStateException("The loaded-style generation changed during add")
      lifecycle.serialized { requireStyleHandleLocked(binding) }
      val handle = checkNotNull(refreshSourcesAfterCommand(binding)[source.id])
      committed = true
      return handle
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not add source '${source.id}': ${error.message}", error)
    } finally {
      lifecycle.serialized {
        if (!committed && imperativeSources[source.id] === record) {
          imperativeSources.remove(source.id)
        }
        completeStyleMutation(reservation)
      }
    }
  }

  internal fun removeStyleSource(id: String): Boolean {
    val reservation = StyleMutationReservation()
    val binding = lifecycle.serialized {
      requireOpenLocked()
      requireNoDesiredSource(id)
      requireNoActiveStyleMutation()
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked).also {
        activeStyleMutation = reservation
      }
    }
    try {
      if (binding.sourceExists(id) == false) return false
      binding.removeSource(id)
      lifecycle.serialized {
        requireStyleHandleLocked(binding)
        imperativeSources.remove(id)
        style.invalidateSourceIdentities(setOf(id))
      }
      refreshSourcesAfterCommand(binding)
      return true
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not remove source '$id': ${error.message}", error)
    } finally {
      lifecycle.serialized { completeStyleMutation(reservation) }
    }
  }

  internal fun addStyleImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    stretch: ImageStretch?,
  ) {
    val record = ImperativeImageRecord()
    val reservation = StyleMutationReservation()
    val binding = lifecycle.serialized {
      requireOpenLocked()
      requireNoDesiredImage(id)
      requireNoActiveStyleMutation()
      if (id in imperativeImages) {
        throw StyleHandleException("Image ID '$id' already exists in style")
      }
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked).also {
        imperativeImages[id] = record
        activeStyleMutation = reservation
      }
    }
    var committed = false
    try {
      if (binding.imageExists(id) == true) {
        throw StyleHandleException("Image ID '$id' already exists in style")
      }
      binding.addImage(id, image, sdf, stretch)
      lifecycle.serialized { requireStyleHandleLocked(binding) }
      committed = true
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not add image '$id': ${error.message}", error)
    } finally {
      lifecycle.serialized {
        if (!committed && imperativeImages[id] === record) imperativeImages.remove(id)
        completeStyleMutation(reservation)
      }
    }
  }

  /**
   * Starts resolution of a missing style image and returns the resolution, or null when no resolver
   * is set, no style is loaded, or this state no longer accepts [adapter].
   *
   * A repeated request for an id already resolving returns that resolution, which the browser needs
   * to keep the request pending while the first call runs.
   *
   * The engine reports the miss from its own thread, so the resolution runs on this state's scope
   * rather than there.
   */
  internal fun resolveMissingImage(adapter: MapAdapter, imageId: String): Deferred<Unit>? =
    lifecycle.serialized {
      if (lifecycle.isClosed || !lifecycle.acceptsAdapter(adapter)) return@serialized null
      val resolver = missingImageResolverState ?: return@serialized null
      val binding = style.currentLoadedStyle() ?: return@serialized null
      missingImageResolutions[imageId]?.let {
        return@serialized it.work
      }
      val token = Any()
      runtime.physicalScope
        .async { supplyMissingImage(resolver, binding, imageId, token) }
        .also { missingImageResolutions[imageId] = MissingImageResolution(token, it) }
    }

  private suspend fun supplyMissingImage(
    resolver: MissingImageResolver,
    binding: StyleBinding,
    imageId: String,
    token: Any,
  ) {
    val resolved =
      try {
        resolver(imageId)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        runtime.logger?.w(error) { "The missing-image resolver failed for image '$imageId'" }
        null
      }
    if (resolved == null) return
    try {
      addResolvedStyleImage(binding, imageId, resolved)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      // A composition can claim the id while the resolver runs. Dropping the record lets a repeated
      // request try again, unless a later resolution already holds the id.
      lifecycle.serialized {
        if (missingImageResolutions[imageId]?.token === token)
          missingImageResolutions.remove(imageId)
      }
      runtime.logger?.w(error) { "Could not add the resolved image '$imageId'" }
    }
  }

  /**
   * Adds a resolved image to [binding].
   *
   * The engine asks as soon as it lays out a tile, so this waits out a style command in progress
   * rather than failing on it, and abandons the add once [binding] is no longer the loaded style.
   * The style that it adds to is not ready yet: the browser counts a style as loaded only once
   * every in-view tile has parsed, and a tile does not finish parsing until this add answers it.
   */
  private suspend fun addResolvedStyleImage(
    binding: StyleBinding,
    imageId: String,
    resolved: ResolvedStyleImage,
  ) {
    val record = ImperativeImageRecord()
    val reservation = StyleMutationReservation()
    while (true) {
      val inProgress = lifecycle.serialized {
        if (lifecycle.isClosed) return
        if (style.currentLoadedStyle() !== binding) return
        (activeStyleMutation ?: backgroundStyleMutation)?.let {
          return@serialized it
        }
        if (hasDesiredImage(imageId) || imageId in imperativeImages) return
        imperativeImages[imageId] = record
        backgroundStyleMutation = reservation
        null
      }
      if (inProgress == null) break
      inProgress.completion.await()
    }
    var committed = false
    try {
      if (binding.imageExists(imageId) == true) return
      binding.addImage(imageId, resolved.image, resolved.sdf, resolved.stretch)
      committed = lifecycle.serialized {
        !lifecycle.isClosed && style.isCurrentLoadedStyle(binding)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      // A style load can invalidate [binding] between the check above and these calls, which fails
      // the add for a style that nothing waits on any more.
      if (lifecycle.serialized { !lifecycle.isClosed && style.isCurrentLoadedStyle(binding) }) {
        if (error is StyleMutationException) {
          throw StyleHandleException("Could not add image '$imageId': ${error.message}", error)
        }
        throw error
      }
    } finally {
      lifecycle.serialized {
        if (!committed && imperativeImages[imageId] === record) imperativeImages.remove(imageId)
        completeStyleMutation(reservation)
      }
    }
  }

  /** Ends every resolution in flight: none of them can still reach the style that asked. */
  private fun cancelMissingImageResolutions() {
    missingImageResolutions.values.forEach { it.work.cancel() }
    missingImageResolutions.clear()
  }

  internal fun removeStyleImage(id: String): Boolean {
    val reservation = StyleMutationReservation()
    val binding = lifecycle.serialized {
      requireOpenLocked()
      requireNoDesiredImage(id)
      requireNoActiveStyleMutation()
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked).also {
        activeStyleMutation = reservation
      }
    }
    try {
      if (binding.imageExists(id) == false) return false
      binding.removeImage(id)
      lifecycle.serialized {
        requireStyleHandleLocked(binding)
        imperativeImages.remove(id)
      }
      return true
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not remove image '$id': ${error.message}", error)
    } finally {
      lifecycle.serialized { completeStyleMutation(reservation) }
    }
  }

  private fun refreshSourcesAfterCommand(binding: StyleBinding): Map<String, SourceHandle> {
    while (true) {
      val read = lifecycle.serialized {
        requireStyleHandleLocked(binding)
        StyleResourceRead(binding, styleHandleEpoch, ++styleSourceChangeRevision)
      }
      val sources = style.readSources(binding)
      val committed = lifecycle.serialized {
        requireStyleHandleLocked(binding)
        if (styleSourceChangeRevision != read.sourceChangeRevision) return@serialized false
        style.updateSources(sources)
        true
      }
      if (committed) return sources
    }
  }

  private fun requireNoDesiredSource(id: String) {
    if (desiredStyleRevision.sources.any { it.id == id }) {
      throw StyleHandleException("Source ID '$id' is declared by the style content")
    }
  }

  private fun hasDesiredImage(id: String): Boolean = desiredStyleRevision.images.any { it.id == id }

  private fun requireNoDesiredImage(id: String) {
    if (hasDesiredImage(id)) {
      throw StyleHandleException("Image ID '$id' is declared by the style content")
    }
  }

  private fun requireNoImperativeResourceConflicts(revision: DesiredStyleRevision) {
    revision.sources
      .firstOrNull { it.id in imperativeSources }
      ?.let {
        throw StyleHandleException("Source ID '${it.id}' is owned by an imperative addition")
      }
    revision.images
      .firstOrNull { it.id in imperativeImages }
      ?.let {
        throw StyleHandleException("Image ID '${it.id}' is owned by an imperative addition")
      }
  }

  private fun requireNoActiveStyleMutation() {
    if (activeStyleMutation != null) {
      throw StyleHandleException("Another imperative style resource command is in progress")
    }
  }

  private fun completeStyleMutation(reservation: StyleMutationReservation) {
    if (activeStyleMutation === reservation) activeStyleMutation = null
    if (backgroundStyleMutation === reservation) backgroundStyleMutation = null
    reservation.completion.complete(Unit)
  }

  internal fun <T> runStyleHandleOperation(
    binding: StyleBinding,
    action: () -> T,
  ): T {
    lifecycle.serialized { requireStyleHandleLocked(binding) }
    val result = action()
    lifecycle.serialized { requireStyleHandleLocked(binding) }
    return result
  }

  internal fun styleHandleCheckpoint(binding: StyleBinding): Long = lifecycle.serialized {
    requireStyleHandleLocked(binding)
    styleHandleEpoch
  }

  internal fun requireStyleHandleUnchanged(binding: StyleBinding, checkpoint: Long) {
    lifecycle.serialized {
      requireStyleHandleLocked(binding)
      check(styleHandleEpoch == checkpoint) {
        "Style operation crossed a loaded-style resource change"
      }
    }
  }

  private fun requireStyleHandleLocked(binding: StyleBinding) {
    requireOpenLocked()
    check(style.loadState == StyleLoadState.Ready && style.isCurrentLoadedStyle(binding)) {
      "Style operation belongs to a stale or unready loaded-style identity"
    }
  }

  /**
   * Publishes the camera and viewport of [adapter] and returns the presentation that holds them. A
   * map with no readable viewport keeps the values it has, so a caller that reacts to a camera
   * event still reaches its presentation.
   */
  internal fun synchronizeCamera(adapter: MapAdapter): MapAttachment? {
    if (!lifecycle.acceptsPresentation(adapter)) return null
    val cameraPosition = adapter.getCameraPosition()
    val viewport = adapter.getViewport()
    return lifecycle.serialized {
      if (!lifecycle.acceptsPresentation(adapter)) return@serialized null
      val current = currentMapAttachment ?: return@serialized null
      if (viewport != null) {
        cameraPositionState = cameraPosition
        current.updateViewport(viewport)
      }
      current
    }
  }

  /**
   * Reacts to one engine event that the lifecycle already accepted, then publishes it to [events].
   * Ignores an [adapter] that this state no longer accepts. Publication follows the reaction, so a
   * collector reads the values that the event produced.
   */
  internal fun onEvent(adapter: MapAdapter, event: MapEvent) {
    val accepted =
      when (event) {
        is MapEvent.CameraMoveStarted ->
          synchronizeCamera(adapter)?.also { it.cameraChangeStarted() } != null
        MapEvent.CameraMoved -> synchronizeCamera(adapter) != null
        is MapEvent.CameraMoveEnded ->
          synchronizeCamera(adapter)?.also { it.cameraChangeEnded() } != null
        is MapEvent.FrameRendered -> lifecycle.acceptsPresentation(adapter)
        MapEvent.StyleLoaded,
        is MapEvent.StyleLoadFailed,
        MapEvent.Idle -> lifecycle.acceptsAdapter(adapter)
      }
    if (accepted) eventsFlow.tryEmit(event)
  }

  /** Reports whether a gesture holds the camera of [adapter]. */
  internal fun setGestureActive(adapter: MapAdapter, active: Boolean) {
    presentedAttachment(adapter)?.setGestureActive(active)
  }

  /** Ends a camera change that the engine behind [adapter] will never finish. */
  internal fun endCameraChange(adapter: MapAdapter) {
    presentedAttachment(adapter)?.cameraChangeEnded()
  }

  private fun presentedAttachment(adapter: MapAdapter): MapAttachment? = lifecycle.serialized {
    if (!lifecycle.acceptsPresentation(adapter)) return@serialized null
    currentMapAttachment
  }

  internal fun isCurrent(candidate: MapAttachment): Boolean = lifecycle.serialized {
    isCurrentLocked(candidate)
  }

  internal fun <T> withCurrentOrNull(candidate: MapAttachment, block: () -> T): T? {
    if (!isCurrent(candidate)) return null
    val result = block()
    return result.takeIf { isCurrent(candidate) }
  }

  private fun isCurrentLocked(candidate: MapAttachment): Boolean =
    currentMapAttachment === candidate && lifecycle.isCurrent(candidate.token, candidate.adapter)

  private fun requireOpenLocked() {
    check(!lifecycle.isClosed) { "The map state is closed" }
  }

  internal fun commitClosed() {
    styleHandleEpoch++
    cancelMissingImageResolutions()
    style.invalidateLoadedStyle()
    Snapshot.withMutableSnapshot {
      closedState = true
      currentMapAttachment?.invalidate()
      currentMapAttachment = null
      nextMapAttachment.completeExceptionally(
        CancellationException("The map closed while waiting for an attachment")
      )
    }
  }

  internal fun invalidatePresentation(adapter: MapAdapter?) {
    Snapshot.withMutableSnapshot {
      currentMapAttachment?.invalidate()
      currentMapAttachment = null
      prepareForNextAttachment()
      if (adapter?.retainsEngineBetweenPresentations != true) {
        style.loadState = StyleLoadState.Pending
      }
    }
  }

  internal fun invalidateClosedAdapter(adapter: MapAdapter) {
    Snapshot.withMutableSnapshot {
      styleHandleEpoch++
      style.invalidateLoadedStyle()
      style.loadState = StyleLoadState.Pending
      if (currentMapAttachment?.adapter === adapter) {
        currentMapAttachment?.invalidate()
        currentMapAttachment = null
        prepareForNextAttachment()
      }
    }
  }

  internal fun configurePresentationAdapter(adapter: MapAdapter) {
    val configuration = lifecycle.serialized {
      if (!lifecycle.isPendingPublication(adapter)) return
      PresentationConfiguration(
        camera = CameraCommand(adapter, cameraPositionState, cameraCommandRevision),
        baseStyle = BaseStyleCommand(adapter, style.baseStyle, baseStyleCommandRevision),
      )
    }
    applyCameraCommand(configuration.camera)
    if (!lifecycle.isPendingPublication(adapter)) return
    applyBaseStyleCommand(configuration.baseStyle)
  }

  internal fun beginStyleLoadForNewAdapter() {
    styleHandleEpoch++
    style.invalidateLoadedStyle()
    style.loadState = StyleLoadState.Loading
  }

  internal fun seedPresentationViewport(token: MapPresentationToken, adapter: MapAdapter) {
    val viewport = adapter.getViewport() ?: return
    lifecycle.serialized {
      val current = currentMapAttachment ?: return@serialized
      if (current.token != token || current.adapter !== adapter || current.viewport != null) return
      current.updateViewport(viewport)
    }
  }

  private fun applyBaseStyleCommand(initial: BaseStyleCommand) {
    var command = initial
    while (true) {
      if (lifecycle.currentAdapter() !== command.adapter) return
      command.adapter.setBaseStyle(command.value)
      command = lifecycle.serialized {
        if (lifecycle.currentAdapter() !== command.adapter) return
        if (baseStyleCommandRevision == command.revision) return
        BaseStyleCommand(command.adapter, style.baseStyle, baseStyleCommandRevision)
      }
    }
  }

  private fun applyCameraCommand(initial: CameraCommand) {
    var command = initial
    while (true) {
      if (lifecycle.currentAdapter() !== command.adapter) return
      command.adapter.setCameraPosition(command.value)
      command = lifecycle.serialized {
        if (lifecycle.currentAdapter() !== command.adapter) return
        if (cameraCommandRevision == command.revision) return
        CameraCommand(command.adapter, cameraPositionState, cameraCommandRevision)
      }
    }
  }

  private fun applyAttachmentCameraCommand(
    attachment: MapAttachment,
    initial: CameraCommand,
  ) {
    var command = initial
    while (true) {
      if (!lifecycle.isCurrent(attachment.token, command.adapter)) return
      command.adapter.setCameraPosition(command.value)
      command = lifecycle.serialized {
        if (!lifecycle.isCurrent(attachment.token, command.adapter)) return
        if (cameraCommandRevision == command.revision) return
        CameraCommand(command.adapter, cameraPositionState, cameraCommandRevision)
      }
    }
  }

  internal fun commitPresentation(
    token: MapPresentationToken,
    adapter: MapAdapter,
  ) {
    val attachment = MapAttachment(this, token, adapter)
    currentMapAttachment = attachment
    nextMapAttachment.complete(attachment)
  }

  private suspend fun awaitAttachment(): MapAttachment {
    val pending = lifecycle.serialized {
      requireOpenLocked()
      currentMapAttachment?.let {
        return it
      }
      nextMapAttachment
    }
    return pending.await()
  }

  private suspend fun awaitReplacementAttachment(): MapAttachment {
    val pending = lifecycle.serialized {
      if (lifecycle.isClosed) {
        throw CancellationException("The map closed during the operation")
      }
      currentMapAttachment?.let {
        return it
      }
      nextMapAttachment
    }
    return pending.await()
  }

  private fun prepareForNextAttachment() {
    if (nextMapAttachment.isCompleted) nextMapAttachment = CompletableDeferred()
  }

  private inline fun <T> withAttachmentRead(block: (MapAttachment) -> T?): T? {
    val attachment = currentMapAttachment ?: return null
    return try {
      block(attachment)
    } catch (_: MapAttachmentChangedException) {
      null
    }
  }

  private data class PresentationConfiguration(
    val camera: CameraCommand,
    val baseStyle: BaseStyleCommand,
  )

  private data class CameraCommand(
    val adapter: MapAdapter,
    val value: CameraPosition,
    val revision: Long,
  )

  private data class AttachmentCameraCommand(
    val attachment: MapAttachment,
    val command: CameraCommand,
  )

  private data class BaseStyleCommand(
    val adapter: MapAdapter,
    val value: BaseStyle,
    val revision: Long,
  )

  private data class StyleResourceRead(
    val binding: StyleBinding,
    val styleHandleEpoch: Long,
    val sourceChangeRevision: Long,
  )
}

@JvmInline internal value class MapPresentationToken(val value: Long)

internal class MapPresentationOwnerToken

/**
 * Remembers a logical map and closes it when this call leaves composition.
 *
 * [baseStyle] and [content] define the desired style. Changes to these inputs update the remembered
 * map. Restoration creates a new map with the saved camera position and the current style inputs.
 *
 * [content] declares the map's sources, layers, and images. It reads the returned state through
 * [LocalMapState] and its viewport through [LocalViewport].
 */
@Composable
public fun rememberMapState(
  runtime: MapRuntime = rememberDefaultMapRuntime(),
  baseStyle: BaseStyle = BaseStyle.Demo,
  initialCameraPosition: CameraPosition = CameraPosition(),
  content: @Composable @MaplibreComposable () -> Unit = {},
): MapState {
  val currentContent by rememberUpdatedState(content)
  val stableContent = remember<@Composable @MaplibreComposable () -> Unit> { { currentContent() } }
  val state =
    rememberSaveable(runtime, saver = mapStateSaver(runtime, baseStyle, stableContent)) {
      runtime.createMapState(
        baseStyle = baseStyle,
        initialCameraPosition = initialCameraPosition,
        content = stableContent,
      )
    }
  SideEffect {
    if (state.style.baseStyle != baseStyle) state.style.baseStyle = baseStyle
  }
  DisposableEffect(state) { onDispose { state.close() } }
  return state
}

private data class SavedCameraPosition(
  val bearing: Double,
  val longitude: Double,
  val latitude: Double,
  val tilt: Double,
  val zoom: Double,
)

private fun mapStateSaver(
  runtime: MapRuntime,
  baseStyle: BaseStyle,
  content: @Composable @MaplibreComposable () -> Unit,
): Saver<MapState, List<Double>> =
  Saver(
    save = { state ->
      state.cameraPosition.toSavedCameraPosition().toList()
    },
    restore = { values ->
      val saved = values.toSavedCameraPosition()
      runtime.createMapState(
        baseStyle = baseStyle,
        initialCameraPosition =
          CameraPosition(
            bearing = saved.bearing,
            target = Position(longitude = saved.longitude, latitude = saved.latitude),
            tilt = saved.tilt,
            zoom = saved.zoom,
          ),
        content = content,
      )
    },
  )

private fun CameraPosition.toSavedCameraPosition(): SavedCameraPosition =
  SavedCameraPosition(bearing, target.longitude, target.latitude, tilt, zoom)

private fun SavedCameraPosition.toList(): List<Double> =
  listOf(bearing, longitude, latitude, tilt, zoom)

private fun List<Double>.toSavedCameraPosition(): SavedCameraPosition {
  require(size == 5) { "A saved camera position must contain five values" }
  return SavedCameraPosition(
    bearing = this[0],
    longitude = this[1],
    latitude = this[2],
    tilt = this[3],
    zoom = this[4],
  )
}

internal fun interface MapRuntimeResources {
  suspend fun close()
}

internal class RuntimeImplementation(
  internal val platformOptions: Any?,
  private val resources: MapRuntimeResources,
  internal val logger: MapLog?,
  offlineManagerBackend: OfflineManager = UnsupportedOfflineManager,
  internal val physicalScope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default),
  internal val snapshotterAdapterFactory: SnapshotterAdapterFactory =
    UnsupportedSnapshotterAdapterFactory,
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
    initialCameraPosition: CameraPosition,
    content: @Composable @MaplibreComposable () -> Unit,
  ): MapState = lock.withLock {
    requireOpenLocked()
    MapState(this, initialCameraPosition, baseStyle, content).also(children::add)
  }

  final override fun createSnapshotter(
    baseStyle: BaseStyle,
    content: @Composable @MaplibreComposable () -> Unit,
  ): MapSnapshotter = lock.withLock {
    requireOpenLocked()
    MapSnapshotterImplementation(this, baseStyle, content).also(snapshotters::add)
  }

  final override fun setRequestInterceptor(interceptor: MapRequestInterceptor?) {
    lock.withLock {
      requireOpenLocked()
      resourceConfig.setInterceptor(interceptor)
    }
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
      runCatching { resources.close() }.exceptionOrNull()?.let(failures::addCleanupFailure)
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
