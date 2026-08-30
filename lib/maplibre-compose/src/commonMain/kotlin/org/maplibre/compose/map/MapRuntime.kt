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
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
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
import org.maplibre.compose.sources.SourceHandle
import org.maplibre.compose.sources.sourceHandle
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding
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

/** Returns the default runtime for this process. */
@Composable public expect fun rememberMapRuntime(): MapRuntime

/** Creates logical maps that share one application-level configuration. */
public interface MapRuntime {
  /** Creates a logical map. The caller must close the result. */
  public fun createMapState(
    initialCameraPosition: CameraPosition = CameraPosition(),
    initialBaseStyle: BaseStyle = BaseStyle.Demo,
  ): MapState

  /** Whether [close] has marked this runtime as closed. */
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

/** The load state for the desired base style of one logical map. */
public sealed interface StyleLoadState {
  /** No presentation can currently load the desired style. */
  public data object Pending : StyleLoadState

  /** The current presentation is loading the desired style. */
  public data object Loading : StyleLoadState

  /** The current presentation has loaded the desired style. */
  public data object Ready : StyleLoadState

  /** The current presentation failed to load the desired style. */
  public data class Failed(public val reason: String?) : StyleLoadState
}

/** Desired and applied style state for one [MapState]. */
public class MapStyleState internal constructor(initialBaseStyle: BaseStyle) {
  private var owner: MapState? = null
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

  /** The sources in the current loaded style, in style order. */
  public val sources: Map<String, SourceHandle>
    get() = if (loadState == StyleLoadState.Ready) sourcesState else emptyMap()

  /** Returns a generation-bound handle for [id], or null until the style is ready or if absent. */
  public fun source(id: String): SourceHandle? {
    if (loadState != StyleLoadState.Ready) return null
    val current = loadedStyle.load() ?: return null
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
    if (loadState != StyleLoadState.Ready) return null
    val current = loadedStyle.load() ?: return null
    return current.layerHandle(id, operationGuard(current))
  }

  internal fun attach(owner: MapState) {
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

  internal fun refreshSource(id: String) {
    val refreshed = source(id)
    sourcesState = if (refreshed == null) sourcesState - id else sourcesState + (id to refreshed)
  }

  internal fun refreshSources() {
    val current = loadedStyle.load()
    sourcesState =
      if (loadState != StyleLoadState.Ready || current == null) emptyMap()
      else
        current
          .getSources()
          .mapNotNull { source ->
            sourceHandle(current, source.id)?.let { source.id to it }
          }
          .toMap()
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

/** One temporary connection between a [MapState] and a map surface. */
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
  private var viewportState: Viewport? by mutableStateOf(adapter.getViewport())
  private val firstViewport =
    CompletableDeferred<Viewport>().also { readiness -> viewportState?.let(readiness::complete) }
  private var cameraMovingState: Boolean by mutableStateOf(false)
  private var moveReasonState: CameraMoveReason by mutableStateOf(CameraMoveReason.NONE)
  private var optionsState: MapPresentationOptions by
    mutableStateOf(initialOptions, structuralEqualityPolicy())

  /** Whether this presentation is still the current connection. */
  public val isValid: Boolean
    get() = validState

  /** The current viewport, or null before the presentation has rendered its first viewport. */
  public val viewport: Viewport?
    get() = viewportState

  /** Whether the current camera mutation is still in progress. */
  public val isCameraMoving: Boolean
    get() = cameraMovingState

  /** The reason that the current or most recent camera mutation started. */
  public val cameraMoveReason: CameraMoveReason
    get() = moveReasonState

  /** The settings that the current [MaplibreMap] call applies to this render lease. */
  public val options: MapPresentationOptions
    get() = optionsState

  /** Sets the camera for this presentation, or fails if its render lease has ended. */
  public fun setCameraPosition(position: CameraPosition) {
    owner.setCameraPosition(this, position)
  }

  /** Fits [boundingBox] in the current viewport and keeps the presentation camera padding. */
  public fun setCameraPosition(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
  ) {
    owner.withCurrent(this) { adapter.setCameraPosition(boundingBox, bearing, tilt, padding) }
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

/** One logical map, independent from its temporary UI presentation. */
public class MapState
internal constructor(
  internal val runtime: RuntimeImplementation,
  initialCameraPosition: CameraPosition,
  initialBaseStyle: BaseStyle,
) {
  private val lock = reentrantLock()
  private val closure = CompletableDeferred<Result<Unit>>()
  private var attachment: Attachment? = null
  private var retainedAdapter: MapAdapter? = null
  private val retiringAdapters = linkedSetOf<MapAdapter>()
  private val pendingCleanupFailures = mutableListOf<Throwable>()
  private var closed = false
  private var styleHandleEpoch = 0L
  private var closedState: Boolean by mutableStateOf(false)
  private var cameraPositionState: CameraPosition by
    mutableStateOf(initialCameraPosition, structuralEqualityPolicy())

  internal var desiredStyleRevision: DesiredStyleRevision = DesiredStyleRevision.Empty

  public val style: MapStyleState = MapStyleState(initialBaseStyle).also { it.attach(this) }

  public val cameraPosition: CameraPosition
    get() = cameraPositionState

  public var presentation: MapPresentation? by mutableStateOf(null)
    private set

  public val isClosed: Boolean
    get() = closedState

  /** Marks this state as closed and starts cleanup of the current presentation. */
  public fun close() {
    val (maps, recordedFailures) =
      lock.withLock {
        if (closed) return
        closed = true
        val maps = buildSet {
          retainedAdapter?.let(::add)
          attachment?.adapter?.let(::add)
          addAll(retiringAdapters)
        }
        val recordedFailures = pendingCleanupFailures.toList()
        attachment = null
        retainedAdapter = null
        retiringAdapters.clear()
        pendingCleanupFailures.clear()
        styleHandleEpoch++
        style.invalidateLoadedStyle()
        Snapshot.withMutableSnapshot {
          closedState = true
          presentation?.invalidate()
          presentation = null
        }
        maps to recordedFailures
      }
    if (maps.isEmpty()) {
      completeClosure(
        if (recordedFailures.isEmpty()) Result.success(Unit)
        else Result.failure(MapStateCleanupException(recordedFailures))
      )
      return
    }
    maps.forEach(MapAdapter::close)
    runtime.physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val failures = recordedFailures.toMutableList()
      maps.forEach { map ->
        runCatching { map.awaitClosed() }.exceptionOrNull()?.let(failures::add)
      }
      completeClosure(
        if (failures.isEmpty()) Result.success(Unit)
        else Result.failure(MapStateCleanupException(failures))
      )
    }
  }

  /** Waits until presentation cleanup has completed. */
  public suspend fun awaitClosed() {
    closure.await().getOrThrow()
  }

  internal fun reservePresentation(
    owner: MapPresentationOwnerToken = MapPresentationOwnerToken()
  ): MapPresentationToken {
    var replaced: MapAdapter? = null
    val token = lock.withLock {
      requireOpenLocked()
      val current = attachment
      check(current == null || current.owner === owner) {
        "The map state already has a presentation"
      }
      replaced = current?.adapter
      if (replaced != null) {
        presentation?.invalidate()
        presentation = null
        if (!replaced.retainsEngineBetweenPresentations) {
          style.loadState = StyleLoadState.Pending
        }
      }
      val token = MapPresentationToken(nextPresentationToken.incrementAndFetch())
      attachment = Attachment(owner, token)
      token
    }
    replaced?.let { adapter ->
      runtime.physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { adapter.detachPresentation() }
      }
    }
    return token
  }

  internal fun publishPresentation(
    token: MapPresentationToken,
    adapter: MapAdapter,
    options: MapPresentationOptions = MapPresentationOptions(),
  ) {
    val replaced = lock.withLock {
      if (closed) return
      requireOpenLocked()
      val current = attachment
      check(current?.token == token && !current.releasing) {
        "The map presentation reservation is no longer current"
      }
      if (current.adapter === adapter) {
        presentation?.updateOptions(options)
        return
      }
      check(current.adapter == null) { "The map state already has a presentation" }
      current.adapter = adapter
      val reusesRetainedAdapter = retainedAdapter === adapter
      val replaced = retainedAdapter?.takeUnless { retained ->
        retained === adapter || !adapter.retainsEngineBetweenPresentations
      }
      if (adapter.retainsEngineBetweenPresentations) retainedAdapter = adapter
      if (replaced != null) retiringAdapters += replaced
      adapter.setCameraPosition(cameraPositionState)
      if (!reusesRetainedAdapter) {
        styleHandleEpoch++
        style.invalidateLoadedStyle()
      }
      adapter.setBaseStyle(style.baseStyle)
      if (!reusesRetainedAdapter) style.loadState = StyleLoadState.Loading
      MapPresentation(this, token, adapter, options).also { presentation = it }
      replaced
    }
    if (replaced != null) {
      replaced.close()
      runtime.physicalScope.launch {
        val failure = runCatching { replaced.awaitClosed() }.exceptionOrNull()
        lock.withLock {
          retiringAdapters.remove(replaced)
          if (failure != null && !closed) pendingCleanupFailures += failure
        }
      }
    }
  }

  internal fun releasePresentation(token: MapPresentationToken, adapter: MapAdapter? = null) {
    val closingAdapter = lock.withLock {
      val current = attachment ?: return
      if (current.token != token) return
      if (adapter != null && current.adapter !== adapter) return
      if (current.releasing) return
      current.releasing = true
      presentation?.invalidate()
      presentation = null
      if (current.adapter?.retainsEngineBetweenPresentations != true) {
        style.loadState = StyleLoadState.Pending
      }
      current.adapter
    }
    if (closingAdapter == null) {
      lock.withLock { if (attachment?.token == token) attachment = null }
      return
    }
    runtime.physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      runCatching { closingAdapter.detachPresentation() }
      lock.withLock { if (attachment?.token == token) attachment = null }
    }
  }

  internal fun retainedAdapter(compatibilityKey: Any): MapAdapter? = lock.withLock {
    retainedAdapter?.takeIf { adapter ->
      adapter.retainsEngineBetweenPresentations &&
        adapter.presentationCompatibilityKey == compatibilityKey
    }
  }

  internal fun durableStyleCallbacks(): MapAdapter.Callbacks = DurableStyleCallbacks(this)

  internal fun markStyleReady(adapter: MapAdapter): Boolean = lock.withLock {
    if (closed || (attachment?.adapter !== adapter && retainedAdapter !== adapter)) return false
    style.loadState = StyleLoadState.Ready
    style.refreshSources()
    true
  }

  internal fun acceptsPresentationEvent(adapter: MapAdapter): Boolean = lock.withLock {
    !closed && attachment?.adapter === adapter && presentation?.adapter === adapter
  }

  internal fun refreshStyleSources(adapter: MapAdapter, sourceId: String?): Boolean =
    lock.withLock {
      if (closed || (attachment?.adapter !== adapter && retainedAdapter !== adapter)) return false
      if (sourceId == null) style.refreshSources() else style.refreshSource(sourceId)
      true
    }

  internal fun updateLoadedStyle(adapter: MapAdapter, loadedStyle: StyleBinding?): Boolean =
    lock.withLock {
      if (closed || (attachment?.adapter !== adapter && retainedAdapter !== adapter)) return false
      styleHandleEpoch++
      style.updateLoadedStyle(loadedStyle)
      true
    }

  internal fun markStyleFailed(adapter: MapAdapter, reason: String?) {
    lock.withLock {
      if (!closed && (attachment?.adapter === adapter || retainedAdapter === adapter)) {
        style.loadState = StyleLoadState.Failed(reason)
      }
    }
  }

  internal fun beginStyleRevision(adapter: MapAdapter, revision: DesiredStyleRevision) {
    lock.withLock {
      if (!closed && (attachment?.adapter === adapter || retainedAdapter === adapter)) {
        styleHandleEpoch++
        desiredStyleRevision = revision
        style.loadState = StyleLoadState.Loading
      }
    }
  }

  internal fun setBaseStyle(value: BaseStyle) {
    lock.withLock {
      requireOpenLocked()
      if (style.baseStyle == value) return
      styleHandleEpoch++
      style.setBaseStyleState(value)
      style.invalidateLoadedStyle()
      val adapter = attachment?.adapter ?: retainedAdapter
      if (adapter == null) {
        style.loadState = StyleLoadState.Pending
      } else {
        style.loadState = StyleLoadState.Loading
        adapter.setBaseStyle(value)
      }
    }
  }

  internal fun desiredSourceDefinition(id: String) =
    desiredStyleRevision.sources.firstOrNull { it.id == id }

  internal fun <T> runStyleHandleOperation(binding: StyleBinding, action: () -> T): T =
    lock.withLock {
      requireStyleHandleLocked(binding)
      action()
    }

  internal fun styleHandleCheckpoint(binding: StyleBinding): Long = lock.withLock {
    requireStyleHandleLocked(binding)
    styleHandleEpoch
  }

  internal fun requireStyleHandleUnchanged(binding: StyleBinding, checkpoint: Long) {
    lock.withLock {
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
    withCurrent(candidate) {
      candidate.adapter.setCameraPosition(position)
      cameraPositionState = position
    }
  }

  internal fun synchronizeCamera(adapter: MapAdapter): MapPresentation? = lock.withLock {
    if (closed || attachment?.adapter !== adapter) return null
    cameraPositionState = adapter.getCameraPosition()
    val current = presentation ?: return null
    val viewport = adapter.getViewport() ?: return null
    current.cameraMoved(viewport)
    current
  }

  internal fun requireCurrent(candidate: MapPresentation) {
    lock.withLock { requireCurrentLocked(candidate) }
  }

  internal fun <T> withCurrent(candidate: MapPresentation, block: () -> T): T = lock.withLock {
    requireCurrentLocked(candidate)
    block()
  }

  private fun requireCurrentLocked(candidate: MapPresentation) {
    if (closed || presentation !== candidate || attachment?.token != candidate.token) {
      throw MapPresentationDetachedException()
    }
  }

  private fun requireOpenLocked() {
    if (closed) throw MapStateClosedException()
  }

  private fun completeClosure(result: Result<Unit>) {
    if (closure.complete(result)) runtime.childClosed(this)
  }

  private class Attachment(
    val owner: MapPresentationOwnerToken,
    val token: MapPresentationToken,
    var adapter: MapAdapter? = null,
    var releasing: Boolean = false,
  )

  private companion object {
    val nextPresentationToken = AtomicLong(0L)
  }
}

internal class MapStateCleanupException(failures: List<Throwable>) :
  RuntimeException("Map state cleanup failed in ${failures.size} resource(s)", failures.first()) {
  init {
    failures.drop(1).forEach(::addSuppressed)
  }
}

@JvmInline internal value class MapPresentationToken(val value: Long)

internal class MapPresentationOwnerToken

/**
 * Remembers a logical map and closes it when this call leaves composition. Restoration creates a
 * new map with the saved camera position and the caller's current [initialBaseStyle].
 */
@Composable
public fun rememberMapState(
  runtime: MapRuntime = rememberMapRuntime(),
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
  internal val physicalScope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MapRuntime {
  private val lock = reentrantLock()
  private val children = linkedSetOf<MapState>()
  private val closure = CompletableDeferred<Result<Unit>>()
  private var closed = false
  private var closedState: Boolean by mutableStateOf(false)

  final override fun createMapState(
    initialCameraPosition: CameraPosition,
    initialBaseStyle: BaseStyle,
  ): MapState = lock.withLock {
    if (closed) throw MapRuntimeClosedException()
    MapState(this, initialCameraPosition, initialBaseStyle).also(children::add)
  }

  override fun close() {
    val closingChildren = lock.withLock {
      if (closed) return
      closed = true
      Snapshot.withMutableSnapshot { closedState = true }
      children.toList()
    }
    closingChildren.forEach(MapState::close)
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val failures = mutableListOf<Throwable>()
      closingChildren.forEach { child ->
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
}

internal class MapRuntimeCleanupException(failures: List<Throwable>) :
  RuntimeException("Map runtime cleanup failed in ${failures.size} resource(s)", failures.first()) {
  init {
    failures.drop(1).forEach(::addSuppressed)
  }
}

internal fun mapRuntimeForTest(closeResources: suspend () -> Unit = {}): MapRuntime =
  RuntimeImplementation(
    platformOptions = null,
    resources = MapRuntimeResources(closeResources),
    logger = null,
  )
