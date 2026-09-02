@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
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
import org.maplibre.compose.sources.SourceHandle
import org.maplibre.compose.sources.sourceHandle
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleComposition
import org.maplibre.compose.style.StyleHandleOperationGuard
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

  /** Creates a logical map. The caller must close the result. */
  public fun createMapState(
    initialCameraPosition: CameraPosition = CameraPosition(),
    initialBaseStyle: BaseStyle = BaseStyle.Demo,
  ): MapState

  /** Creates an independent non-UI map for image capture. The caller must close the result. */
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

/** Thrown when an operation targets a presentation whose render lease has ended. */
public class MapPresentationDetachedException :
  IllegalStateException("The map presentation lease has ended")

/** Reports the load state for the desired base style of one logical map. */
public sealed interface StyleLoadState {
  /** No presentation can currently load the desired style. */
  public data object Pending : StyleLoadState

  /** Indicates that the current presentation is loading the desired style. */
  public data object Loading : StyleLoadState

  /** Indicates that the current presentation loaded the desired style. */
  public data object Ready : StyleLoadState

  /** Indicates that the current presentation failed to load the desired style. */
  public data class Failed(public val reason: String?) : StyleLoadState
}

internal interface MapStyleStateOwner {
  fun setBaseStyle(value: BaseStyle)

  fun desiredSourceDefinition(id: String): org.maplibre.compose.style.SourceDefinition?

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
  private var baseStyleState: BaseStyle by
    mutableStateOf(initialBaseStyle, structuralEqualityPolicy())

  public var baseStyle: BaseStyle
    get() = baseStyleState
    set(value) {
      owner?.setBaseStyle(value) ?: setBaseStyleState(value)
    }

  public var loadState: StyleLoadState by mutableStateOf(StyleLoadState.Pending)
    internal set

  /** Contains the sources in the current loaded style, in style order. */
  public val sources: Map<String, SourceHandle>
    get() = if (readyLoadedStyle() == null) emptyMap() else sourcesState

  /** Returns a generation-bound handle for [id], or null until the style is ready or if absent. */
  public fun source(id: String): SourceHandle? {
    val current = readyLoadedStyle() ?: return null
    return sourceHandle(current, id)
  }

  private fun sourceHandle(current: StyleBinding, id: String): SourceHandle? =
    current.sourceHandle(
      id = id,
      definition = owner?.desiredSourceDefinition(id),
      currentDefinition = { owner?.desiredSourceDefinition(id) },
      operations = operationGuard(current),
    )

  /** Returns a generation-bound handle for [id], or null until the style is ready or if absent. */
  public fun layer(id: String): LayerHandle? {
    val current = readyLoadedStyle() ?: return null
    return current.layerHandle(id, operationGuard(current))
  }

  private fun readyLoadedStyle(): StyleBinding? =
    owner?.readyLoadedStyle() ?: loadedStyle.load()?.takeIf { loadState == StyleLoadState.Ready }

  internal fun attach(owner: MapStyleStateOwner) {
    this.owner = owner
  }

  internal fun setBaseStyleState(value: BaseStyle) {
    baseStyleState = value
  }

  internal fun updateLoadedStyle(style: StyleBinding?) {
    loadedStyle.store(style)
    sourcesState = emptyMap()
  }

  internal fun invalidateLoadedStyle() {
    loadedStyle.exchange(null)?.invalidate()
    sourcesState = emptyMap()
  }

  internal fun isCurrentLoadedStyle(style: StyleBinding): Boolean = loadedStyle.load() === style

  internal fun currentLoadedStyle(): StyleBinding? = loadedStyle.load()

  internal fun refreshSources() {
    val current = loadedStyle.load()
    sourcesState =
      if (loadState != StyleLoadState.Ready || current == null) emptyMap() else readSources(current)
  }

  internal fun readSources(current: StyleBinding): Map<String, SourceHandle> =
    current
      .getSources()
      .mapNotNull { source -> sourceHandle(current, source.id)?.let { source.id to it } }
      .toMap()

  internal fun updateSources(sources: Map<String, SourceHandle>) {
    sourcesState = sources
  }

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

/** Represents a temporary connection between a [MapState] and a map surface. */
public class MapPresentation
internal constructor(
  private val owner: MapState,
  internal val token: MapPresentationToken,
  internal val adapter: MapAdapter,
  initialOptions: MapPresentationOptions = MapPresentationOptions(),
) {
  private val invalidated = CompletableDeferred<Unit>()
  private val cameraMutation = MutatorMutex()
  private var validState: Boolean by mutableStateOf(true)
  private var viewportState: Viewport? by mutableStateOf(null)
  private val firstViewport = CompletableDeferred<Viewport>()
  private var cameraMovingState: Boolean by mutableStateOf(false)
  private var moveReasonState: CameraMoveReason by mutableStateOf(CameraMoveReason.NONE)
  private var optionsState: MapPresentationOptions by
    mutableStateOf(initialOptions, structuralEqualityPolicy())

  /** Returns true while this presentation is the current connection. */
  public val isValid: Boolean
    get() = validState

  /** Contains the current viewport, or null before the first rendered viewport. */
  public val viewport: Viewport?
    get() = viewportState

  /** Returns true while a camera mutation is in progress. */
  public val isCameraMoving: Boolean
    get() = cameraMovingState

  /** Contains the reason for the current or most recent camera mutation. */
  public val cameraMoveReason: CameraMoveReason
    get() = moveReasonState

  /** Contains the settings that the current [MaplibreMap] call applies to this render lease. */
  public val options: MapPresentationOptions
    get() = optionsState

  /** Sets the camera for this presentation, or fails if its render lease has ended. */
  public fun setCameraPosition(position: CameraPosition) {
    owner.setCameraPosition(this, position)
  }

  /** Fits [boundingBox] in the current viewport and keeps the presentation camera padding. */
  public suspend fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
  ): Unit = runLeaseBound {
    awaitViewportState()
    adapter.setCameraPosition(boundingBox, bearing, tilt, padding)
  }

  /** Animates the camera to [position]. A new camera animation replaces the previous one. */
  public suspend fun animateCameraPosition(
    position: CameraPosition,
    duration: Duration = 300.milliseconds,
  ) {
    cameraMutation.mutate {
      runLeaseBound { adapter.animateCameraPosition(position, duration) }
    }
  }

  /** Animates the camera to fit [boundingBox]. A new camera animation replaces the previous one. */
  public suspend fun animateCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
    duration: Duration = 300.milliseconds,
  ) {
    cameraMutation.mutate {
      runLeaseBound {
        awaitViewportState()
        adapter.animateCameraPosition(boundingBox, bearing, tilt, padding, duration)
      }
    }
  }

  /** Returns the visible region, or null before this presentation has a viewport. */
  public fun getVisibleRegion(): VisibleRegion? = withViewport { it.getVisibleRegion() }

  /** Returns the visible axis-aligned bounds, or null before this presentation has a viewport. */
  public fun getVisibleBoundingBox(): BoundingBox? = withViewport { it.getVisibleBoundingBox() }

  /** Projects [position] into a logical-pixel offset in this presentation. */
  public fun screenLocationFromPosition(position: Position): DpOffset? = withViewport {
    it.screenLocationFromPosition(position)
  }

  /** Unprojects a logical-pixel [offset] into a geographic position. */
  public fun positionFromScreenLocation(offset: DpOffset): Position? = withViewport {
    it.positionFromScreenLocation(offset)
  }

  /** Returns the ground distance per dp, or null before this presentation has a viewport. */
  public fun metersPerDpAtLatitude(latitude: Double): Double? = withViewport {
    it.metersPerDpAtLatitude(latitude)
  }

  /** Queries rendered features at [offset] in front-to-back render order. */
  public suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> = runLeaseBound {
    awaitViewportState()
    adapter.queryRenderedFeatures(offset, layerIds, predicate.compileOrNull())
  }

  /** Queries rendered features that intersect [rect] in front-to-back render order. */
  public suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> = runLeaseBound {
    awaitViewportState()
    adapter.queryRenderedFeatures(rect, layerIds, predicate.compileOrNull())
  }

  /** Suspends until this presentation has rendered its first viewport. */
  public suspend fun awaitViewport(): Viewport = runLeaseBound { awaitViewportState() }

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

  internal fun updateOptions(value: MapPresentationOptions) {
    optionsState = value
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
    owner.requireCurrent(this@MapPresentation)
    val operation =
      async(start = CoroutineStart.UNDISPATCHED) {
        owner.requireCurrent(this@MapPresentation)
        block()
      }
    select {
      operation.onAwait { it }
      invalidated.onAwait {
        operation.cancelAndJoin()
        throw MapPresentationDetachedException()
      }
    }
  }
}

/** Represents a logical map independently from a temporary UI presentation. */
public class MapState
internal constructor(
  internal val runtime: RuntimeImplementation,
  initialCameraPosition: CameraPosition,
  initialBaseStyle: BaseStyle,
) {
  internal val lifecycle = MapLifecycleAuthority(this, runtime.physicalScope)
  private var baseStyleCommandRevision = 0L
  private var cameraCommandRevision = 0L
  private var styleHandleEpoch = 0L
  private var styleSourceChangeRevision = 0L
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

  public var presentation: MapPresentation? by mutableStateOf(null)
    internal set

  public val isClosed: Boolean
    get() = closedState

  /** Marks this state as closed and starts cleanup of the current presentation. */
  public fun close(): Unit = lifecycle.close()

  /** Waits until presentation cleanup has completed. */
  public suspend fun awaitClosed(): Unit = lifecycle.awaitClosed()

  internal fun reservePresentation(
    owner: MapPresentationOwnerToken = MapPresentationOwnerToken()
  ): MapPresentationToken = lifecycle.reservePresentation(owner)

  internal fun publishPresentation(
    token: MapPresentationToken,
    adapter: MapAdapter,
    options: MapPresentationOptions = MapPresentationOptions(),
  ) = lifecycle.publishPresentation(token, adapter, options)

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
        StyleSourceRead(binding, styleHandleEpoch, styleSourceChangeRevision)
      }
      val sources = runCatching { style.readSources(read.binding) }
      if (sources.isFailure) {
        val stillCurrent = lifecycle.serialized { isCurrentStyleSourceRead(adapter, read) }
        if (!stillCurrent) return false
        throw requireNotNull(sources.exceptionOrNull())
      }
      val committed = lifecycle.serialized {
        if (!isCurrentStyleSourceRead(adapter, read)) return false
        if (style.loadState is StyleLoadState.Failed) return false
        if (styleSourceChangeRevision != read.sourceChangeRevision) return@serialized false
        style.updateSources(sources.getOrThrow())
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
      StyleSourceRead(binding, styleHandleEpoch, sourceChangeRevision)
    }
    val sources = runCatching { style.readSources(read.binding) }
    if (sources.isFailure) {
      val stillCurrent = lifecycle.serialized { isCurrentStyleSourceRead(adapter, read) }
      if (!stillCurrent) return false
      throw requireNotNull(sources.exceptionOrNull())
    }
    return lifecycle.serialized {
      if (!isCurrentStyleSourceRead(adapter, read)) return false
      if (style.loadState != StyleLoadState.Ready) return false
      // A later callback performs its own complete read, preserving source order without allowing
      // this older result to overwrite it.
      if (styleSourceChangeRevision != read.sourceChangeRevision) return true
      style.updateSources(sources.getOrThrow())
      true
    }
  }

  private fun isCurrentStyleSourceRead(adapter: MapAdapter, read: StyleSourceRead): Boolean =
    lifecycle.acceptsAdapter(adapter) &&
      styleHandleEpoch == read.styleHandleEpoch &&
      style.currentLoadedStyle() === read.binding

  internal fun updateLoadedStyle(adapter: MapAdapter, loadedStyle: StyleBinding?): Boolean =
    lifecycle.serialized {
      if (!lifecycle.acceptsAdapter(adapter)) return false
      if (style.currentLoadedStyle() === loadedStyle) return true
      styleHandleEpoch++
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
    desiredStyleRevision.sources.firstOrNull { it.id == id }

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

  internal fun setCameraPosition(candidate: MapPresentation, position: CameraPosition) {
    val command = lifecycle.serialized {
      requireCurrentLocked(candidate)
      cameraPositionState = position
      CameraCommand(candidate.adapter, position, ++cameraCommandRevision)
    }
    applyPresentationCameraCommand(candidate, command)
  }

  internal fun synchronizeCamera(adapter: MapAdapter): MapPresentation? {
    if (!lifecycle.acceptsPresentation(adapter)) return null
    val cameraPosition = adapter.getCameraPosition()
    val viewport = adapter.getViewport() ?: return null
    return lifecycle.serialized {
      if (!lifecycle.acceptsPresentation(adapter)) return@serialized null
      cameraPositionState = cameraPosition
      val current = presentation ?: return@serialized null
      current.cameraMoved(viewport)
      current
    }
  }

  internal fun requireCurrent(candidate: MapPresentation) {
    lifecycle.serialized { requireCurrentLocked(candidate) }
  }

  internal fun <T> withCurrent(candidate: MapPresentation, block: () -> T): T {
    lifecycle.serialized { requireCurrentLocked(candidate) }
    val result = block()
    lifecycle.serialized { requireCurrentLocked(candidate) }
    return result
  }

  private fun requireCurrentLocked(candidate: MapPresentation) {
    if (presentation !== candidate || !lifecycle.isCurrent(candidate.token, candidate.adapter)) {
      throw MapPresentationDetachedException()
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
      presentation?.invalidate()
      presentation = null
    }
  }

  internal fun invalidatePresentation(adapter: MapAdapter?) {
    Snapshot.withMutableSnapshot {
      presentation?.invalidate()
      presentation = null
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
      if (presentation?.adapter === adapter) {
        presentation?.invalidate()
        presentation = null
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
      val current = presentation ?: return@serialized
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

  private fun applyPresentationCameraCommand(
    presentation: MapPresentation,
    initial: CameraCommand,
  ) {
    var command = initial
    while (true) {
      if (!lifecycle.isCurrent(presentation.token, command.adapter)) return
      command.adapter.setCameraPosition(command.value)
      command = lifecycle.serialized {
        if (!lifecycle.isCurrent(presentation.token, command.adapter)) return
        if (cameraCommandRevision == command.revision) return
        CameraCommand(command.adapter, cameraPositionState, cameraCommandRevision)
      }
    }
  }

  internal fun commitPresentation(
    token: MapPresentationToken,
    adapter: MapAdapter,
    options: MapPresentationOptions,
  ) {
    presentation = MapPresentation(this, token, adapter, options)
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

  private data class BaseStyleCommand(
    val adapter: MapAdapter,
    val value: BaseStyle,
    val revision: Long,
  )

  private data class StyleSourceRead(
    val binding: StyleBinding,
    val styleHandleEpoch: Long,
    val sourceChangeRevision: Long,
  )
}

internal class MapStateCleanupException(failures: List<Throwable>) :
  AggregateCleanupException("Map state cleanup failed in ${failures.size} resource(s)", failures)

@JvmInline internal value class MapPresentationToken(val value: Long)

internal class MapPresentationOwnerToken

/**
 * Remembers a logical map and closes it when this call leaves composition. Restoration creates a
 * new map with the saved camera position and the caller's current [initialBaseStyle].
 */
@Composable
public fun rememberMapState(
  runtime: MapRuntime = rememberDefaultMapRuntime(),
  initialCameraPosition: CameraPosition = CameraPosition(),
  initialBaseStyle: BaseStyle = BaseStyle.Demo,
): MapState {
  val state =
    rememberSaveable(
      runtime,
      saver = mapStateSaver(runtime, initialBaseStyle),
    ) {
      runtime.createMapState(initialCameraPosition, initialBaseStyle)
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
  initialBaseStyle: BaseStyle,
): Saver<MapState, List<Double>> =
  Saver(
    save = { state ->
      state.cameraPosition.toSavedCameraPosition().toList()
    },
    restore = { values ->
      val saved = values.toSavedCameraPosition()
      runtime.createMapState(
        initialCameraPosition =
          CameraPosition(
            bearing = saved.bearing,
            target = Position(longitude = saved.longitude, latitude = saved.latitude),
            tilt = saved.tilt,
            zoom = saved.zoom,
          ),
        initialBaseStyle = initialBaseStyle,
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
    initialCameraPosition: CameraPosition,
    initialBaseStyle: BaseStyle,
  ): MapState = lock.withLock {
    requireOpenLocked()
    MapState(this, initialCameraPosition, initialBaseStyle).also(children::add)
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
