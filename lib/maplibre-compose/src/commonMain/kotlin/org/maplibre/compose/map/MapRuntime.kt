@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
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
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
import org.maplibre.compose.style.LocalMapState
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleComposition
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.util.ImageStretch
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** Platform configuration for one [MapRuntime]. */
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
   * A null [interceptor] stops rewriting URLs and headers. The change applies to requests that
   * start after this call returns.
   */
  public fun setRequestInterceptor(interceptor: MapRequestInterceptor?)

  /**
   * Creates a logical map with [baseStyle] and [styleComposition]. The caller must close the
   * result.
   */
  public fun createMapState(
    baseStyle: BaseStyle,
    styleComposition: StyleComposition = StyleComposition.Empty,
    initialCameraPosition: CameraPosition = CameraPosition(),
  ): MapState

  /**
   * Creates an independent non-UI map with [baseStyle] and [styleComposition] for image capture.
   * The caller must close the result.
   */
  public fun createSnapshotter(
    baseStyle: BaseStyle,
    styleComposition: StyleComposition = StyleComposition.Empty,
  ): MapSnapshotter

  /** Returns true after [close] marks this runtime as closed. */
  public val isClosed: Boolean

  /** Marks this runtime as closed and starts child and shared-resource cleanup. */
  public fun close()

  /** Waits until every child and shared resource has finished cleanup. */
  public suspend fun awaitClosed()
}

/** Thrown when an operation targets a closed runtime. */
public class MapRuntimeClosedException : IllegalStateException("The map runtime is closed")

/** Thrown when an operation targets a closed logical map. */
public class MapStateClosedException : IllegalStateException("The map state is closed")

/** Thrown when an operation requires a map that is attached to a UI surface. */
public class MapNotAttachedException :
  IllegalStateException("The map is not attached to a UI surface")

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

  fun sourceDefinitionToken(id: String): Any?

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

  internal fun sourceHandle(id: String): SourceHandle? {
    if (readyLoadedStyle() == null) return null
    return sourcesState[id]
  }

  private fun sourceHandle(current: StyleBinding, id: String): SourceHandle? = owner.let { owner ->
    val definition = owner?.desiredSourceDefinition(id)
    val token = owner?.sourceDefinitionToken(id)
    current.sourceHandle(
      id = id,
      definition = definition,
      currentDefinition = {
        owner?.desiredSourceDefinition(id)?.takeIf { owner.sourceDefinitionToken(id) === token }
      },
      operations = operationGuard(current),
    )
  }

  internal fun layerHandle(id: String): LayerHandle? {
    if (readyLoadedStyle() == null) return null
    return layersState[id]
  }

  private fun readyLoadedStyle(): StyleBinding? =
    owner?.readyLoadedStyle() ?: loadedStyle.load()?.takeIf { loadState == StyleLoadState.Ready }

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

  internal fun readSources(current: StyleBinding): Map<String, SourceHandle> =
    current
      .getSources()
      .mapNotNull { source -> sourceHandle(current, source.id)?.let { source.id to it } }
      .toMap()

  internal fun updateSources(sources: Map<String, SourceHandle>) {
    sourcesState = sources
  }

  internal fun readLayers(current: StyleBinding): Map<String, LayerHandle> =
    current
      .getLayers()
      .mapNotNull { layer ->
        current.layerHandle(layer.id, operationGuard(current))?.let { layer.id to it }
      }
      .toMap()

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
}

internal data class LoadedStyleResources(
  val sources: Map<String, SourceHandle>,
  val layers: Map<String, LayerHandle>,
)

internal class ImperativeSourceRecord(val definition: SourceDefinition)

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
  private var cameraMovingState: Boolean by mutableStateOf(false)
  private var moveReasonState: CameraMoveReason by mutableStateOf(CameraMoveReason.NONE)
  val isValid: Boolean
    get() = validState

  val viewport: Viewport?
    get() = viewportState

  val isCameraMoving: Boolean
    get() = cameraMovingState

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
    viewportState = value
    value?.let(firstViewport::complete)
  }

  internal fun cameraMoveStarted(reason: CameraMoveReason) {
    moveReasonState = reason
    cameraMovingState = true
  }

  internal fun cameraMoved(viewport: Viewport?) {
    updateViewport(viewport)
  }

  internal fun cameraMoveEnded() {
    cameraMovingState = false
  }

  internal fun invalidate() {
    validState = false
    invalidated.complete(Unit)
  }

  private fun Expression<BooleanValue>.compileOrNull(): CompiledExpression<BooleanValue>? {
    if (this == const(true)) return null
    return compile(ExpressionContext.None)
  }

  private fun <T> withViewport(block: (MapAdapter) -> T): T? =
    owner.withCurrent(this) { if (viewportState == null) null else block(adapter) }

  private suspend fun awaitViewportState(): Viewport = firstViewport.await()

  private suspend fun <T> runLeaseBound(block: suspend () -> T): T = coroutineScope {
    owner.requireCurrent(this@MapAttachment)
    val operation =
      async(start = CoroutineStart.UNDISPATCHED) {
        owner.requireCurrent(this@MapAttachment)
        block()
      }
    select {
      operation.onAwait { it }
      invalidated.onAwait {
        operation.cancelAndJoin()
        throw MapNotAttachedException()
      }
    }
  }
}

/** Holds the observable style, camera, and map operations for one logical map. */
@Stable
public class MapState
internal constructor(
  internal val runtime: RuntimeImplementation,
  initialCameraPosition: CameraPosition,
  initialBaseStyle: BaseStyle,
  internal val styleComposition: StyleComposition,
) {
  internal val lifecycle = MapLifecycleAuthority(this, runtime.physicalScope)
  private var baseStyleCommandRevision = 0L
  private var cameraCommandRevision = 0L
  private var styleHandleEpoch = 0L
  private var styleSourceChangeRevision = 0L
  private val imperativeSources = mutableMapOf<String, ImperativeSourceRecord>()
  private val imperativeImageIds = mutableSetOf<String>()
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

          override fun sourceDefinitionToken(id: String) = this@MapState.sourceDefinitionToken(id)

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

  /** Returns true while the current map surface is moving its camera. */
  public val isCameraMoving: Boolean
    get() = currentMapAttachment?.isCameraMoving == true

  /**
   * Contains the reason for the current camera movement, or [CameraMoveReason.NONE] while detached.
   */
  public val cameraMoveReason: CameraMoveReason
    get() = currentMapAttachment?.cameraMoveReason ?: CameraMoveReason.NONE

  public val isClosed: Boolean
    get() = closedState

  /** Marks this state as closed and starts cleanup of the current map surface. */
  public fun close(): Unit = lifecycle.close()

  /** Waits until map-surface cleanup has completed. */
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

  /** Queries rendered features at [offset] in front-to-back render order. */
  public suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    requireAttachment().queryRenderedFeatures(offset, layerIds, predicate)

  /** Queries rendered features that intersect [rect] in front-to-back render order. */
  public suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    requireAttachment().queryRenderedFeatures(rect, layerIds, predicate)

  /** Waits for the first viewport from the current or a future map attachment. */
  public suspend fun awaitViewport(): Viewport =
    retryAcrossAttachments(MapAttachment::awaitViewport)

  private suspend fun <T> retryAcrossAttachments(operation: suspend (MapAttachment) -> T): T {
    while (true) {
      try {
        return operation(awaitAttachment())
      } catch (_: MapNotAttachedException) {
        // A replacement surface can attach after this lease ends.
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
      imperativeImageIds.clear()
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

  internal fun beginStyleRevision(adapter: MapAdapter, revision: DesiredStyleRevision) {
    lifecycle.serialized {
      if (lifecycle.acceptsAdapter(adapter)) {
        requireNoImperativeResourceConflicts(revision)
        styleHandleEpoch++
        desiredStyleRevision = revision
        style.loadState = StyleLoadState.Loading
      }
    }
  }

  internal fun setBaseStyle(value: BaseStyle) {
    val command = lifecycle.serialized {
      requireOpenLocked()
      if (style.baseStyle == value) return
      styleHandleEpoch++
      imperativeSources.clear()
      imperativeImageIds.clear()
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
    desiredStyleRevision.sources.firstOrNull { it.id == id } ?: imperativeSources[id]?.definition

  internal fun sourceDefinitionToken(id: String): Any? = imperativeSources[id]

  internal fun addStyleSource(source: Source): SourceHandle {
    val binding = lifecycle.serialized {
      requireOpenLocked()
      requireNoDesiredSource(source.id)
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked)
    }
    val definition = source.definition()
    if (binding.sourceExists(source.id) == true) {
      throw StyleHandleException("Source ID '${source.id}' already exists in style")
    }
    val added =
      try {
        binding.addSource(definition)
      } catch (error: StyleMutationException) {
        throw StyleHandleException("Could not add source '${source.id}': ${error.message}", error)
      }
    if (!added) throw IllegalStateException("The loaded-style generation changed during add")
    lifecycle.serialized {
      requireStyleHandleLocked(binding)
      imperativeSources[source.id] = ImperativeSourceRecord(definition)
    }
    return checkNotNull(refreshSourcesAfterCommand(binding)[source.id])
  }

  internal fun removeStyleSource(id: String): Boolean {
    val binding = lifecycle.serialized {
      requireOpenLocked()
      requireNoDesiredSource(id)
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked)
    }
    if (binding.sourceExists(id) == false) return false
    try {
      binding.removeSource(id)
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not remove source '$id': ${error.message}", error)
    }
    lifecycle.serialized {
      requireStyleHandleLocked(binding)
      imperativeSources.remove(id)
    }
    refreshSourcesAfterCommand(binding)
    return true
  }

  internal fun addStyleImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    stretch: ImageStretch?,
  ) {
    val binding = lifecycle.serialized {
      requireOpenLocked()
      requireNoDesiredImage(id)
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked)
    }
    if (binding.imageExists(id) == true) {
      throw StyleHandleException("Image ID '$id' already exists in style")
    }
    try {
      binding.addImage(id, image, sdf, stretch)
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not add image '$id': ${error.message}", error)
    }
    lifecycle.serialized {
      requireStyleHandleLocked(binding)
      imperativeImageIds += id
    }
  }

  internal fun removeStyleImage(id: String): Boolean {
    val binding = lifecycle.serialized {
      requireOpenLocked()
      requireNoDesiredImage(id)
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked)
    }
    if (binding.imageExists(id) == false) return false
    try {
      binding.removeImage(id)
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not remove image '$id': ${error.message}", error)
    }
    lifecycle.serialized {
      requireStyleHandleLocked(binding)
      imperativeImageIds -= id
    }
    return true
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
      throw StyleHandleException("Source ID '$id' is owned by StyleComposition")
    }
  }

  private fun requireNoDesiredImage(id: String) {
    if (desiredStyleRevision.images.any { it.id == id }) {
      throw StyleHandleException("Image ID '$id' is owned by StyleComposition")
    }
  }

  private fun requireNoImperativeResourceConflicts(revision: DesiredStyleRevision) {
    revision.sources
      .firstOrNull { it.id in imperativeSources }
      ?.let {
        throw StyleHandleException("Source ID '${it.id}' is owned by an imperative addition")
      }
    revision.images
      .firstOrNull { it.id in imperativeImageIds }
      ?.let {
        throw StyleHandleException("Image ID '${it.id}' is owned by an imperative addition")
      }
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

  internal fun synchronizeCamera(adapter: MapAdapter): MapAttachment? {
    if (!lifecycle.acceptsPresentation(adapter)) return null
    val cameraPosition = adapter.getCameraPosition()
    val viewport = adapter.getViewport() ?: return null
    return lifecycle.serialized {
      if (!lifecycle.acceptsPresentation(adapter)) return@serialized null
      cameraPositionState = cameraPosition
      val current = currentMapAttachment ?: return@serialized null
      current.cameraMoved(viewport)
      current
    }
  }

  internal fun requireCurrent(candidate: MapAttachment) {
    lifecycle.serialized { requireCurrentLocked(candidate) }
  }

  internal fun <T> withCurrent(candidate: MapAttachment, block: () -> T): T {
    lifecycle.serialized { requireCurrentLocked(candidate) }
    val result = block()
    lifecycle.serialized { requireCurrentLocked(candidate) }
    return result
  }

  private fun requireCurrentLocked(candidate: MapAttachment) {
    if (
      currentMapAttachment !== candidate || !lifecycle.isCurrent(candidate.token, candidate.adapter)
    ) {
      throw MapNotAttachedException()
    }
  }

  private fun requireOpenLocked() {
    if (lifecycle.isClosed) throw MapStateClosedException()
  }

  internal fun commitClosed() {
    styleHandleEpoch++
    style.invalidateLoadedStyle()
    Snapshot.withMutableSnapshot {
      closedState = true
      currentMapAttachment?.invalidate()
      currentMapAttachment = null
      nextMapAttachment.completeExceptionally(MapStateClosedException())
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

  private fun requireAttachment(): MapAttachment =
    currentMapAttachment ?: throw MapNotAttachedException()

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

  private fun prepareForNextAttachment() {
    if (nextMapAttachment.isCompleted) nextMapAttachment = CompletableDeferred()
  }

  private inline fun <T> withAttachmentRead(block: (MapAttachment) -> T?): T? {
    val attachment = currentMapAttachment ?: return null
    return try {
      block(attachment)
    } catch (_: MapNotAttachedException) {
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

internal class MapStateCleanupException(failures: List<Throwable>) :
  AggregateCleanupException("Map state cleanup failed in ${failures.size} resource(s)", failures)

@JvmInline internal value class MapPresentationToken(val value: Long)

internal class MapPresentationOwnerToken

/** Receiver for the trailing style block of [rememberMapState]. */
@Stable
public interface MapStyleScope {
  /** The logical map whose style this composition defines. */
  public val mapState: MapState
}

private class MapStyleScopeImpl(override val mapState: MapState) : MapStyleScope

/**
 * Remembers a logical map and closes it when this call leaves composition.
 *
 * [baseStyle], [styleComposition], and [content] define the desired style. Changes to these inputs
 * update the remembered map. Restoration creates a new map with the saved camera position and the
 * current style inputs.
 *
 * [content] adds sources and layers after [styleComposition]. Its [MapStyleScope.mapState] value
 * refers to the returned state when the library evaluates the block.
 */
@Composable
public fun rememberMapState(
  runtime: MapRuntime = rememberDefaultMapRuntime(),
  baseStyle: BaseStyle = BaseStyle.Demo,
  styleComposition: StyleComposition = StyleComposition.Empty,
  initialCameraPosition: CameraPosition = CameraPosition(),
  content: @Composable @MaplibreComposable MapStyleScope.() -> Unit = {},
): MapState {
  val currentStyleComposition by rememberUpdatedState(styleComposition)
  val currentContent by rememberUpdatedState(content)
  val combinedStyleComposition = remember {
    StyleComposition {
      currentStyleComposition.content()
      val mapState = checkNotNull(LocalMapState.current)
      with(MapStyleScopeImpl(mapState)) { currentContent() }
    }
  }
  val state =
    rememberSaveable(
      runtime,
      saver = mapStateSaver(runtime, baseStyle, combinedStyleComposition),
    ) {
      runtime.createMapState(
        baseStyle = baseStyle,
        styleComposition = combinedStyleComposition,
        initialCameraPosition = initialCameraPosition,
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
  styleComposition: StyleComposition,
): Saver<MapState, List<Double>> =
  Saver(
    save = { state ->
      state.cameraPosition.toSavedCameraPosition().toList()
    },
    restore = { values ->
      val saved = values.toSavedCameraPosition()
      runtime.createMapState(
        baseStyle = baseStyle,
        styleComposition = styleComposition,
        initialCameraPosition =
          CameraPosition(
            bearing = saved.bearing,
            target = Position(longitude = saved.longitude, latitude = saved.latitude),
            tilt = saved.tilt,
            zoom = saved.zoom,
          ),
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
  internal val logger: Logger?,
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
    styleComposition: StyleComposition,
    initialCameraPosition: CameraPosition,
  ): MapState = lock.withLock {
    requireOpenLocked()
    MapState(this, initialCameraPosition, baseStyle, styleComposition).also(children::add)
  }

  final override fun createSnapshotter(
    baseStyle: BaseStyle,
    styleComposition: StyleComposition,
  ): MapSnapshotter = lock.withLock {
    requireOpenLocked()
    MapSnapshotterImplementation(this, baseStyle, styleComposition).also(snapshotters::add)
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
    if (closed) throw MapRuntimeClosedException()
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
        runCatching { child.awaitClosed() }.exceptionOrNull()?.let(failures::add)
      }
      closingSnapshotters.forEach { child ->
        runCatching { child.awaitClosed() }.exceptionOrNull()?.let(failures::add)
      }
      runCatching { resources.close() }.exceptionOrNull()?.let(failures::add)
      closure.complete(
        if (failures.isEmpty()) Result.success(Unit)
        else Result.failure(MapRuntimeCleanupException(failures))
      )
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

internal class MapRuntimeCleanupException(failures: List<Throwable>) :
  AggregateCleanupException("Map runtime cleanup failed in ${failures.size} resource(s)", failures)
