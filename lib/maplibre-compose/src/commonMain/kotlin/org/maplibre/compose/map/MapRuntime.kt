@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.jvm.JvmInline
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleState
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
  private var baseStyleState: BaseStyle by
    mutableStateOf(initialBaseStyle, structuralEqualityPolicy())

  public var baseStyle: BaseStyle
    get() = baseStyleState
    set(value) {
      owner?.setBaseStyle(value) ?: setBaseStyleState(value)
    }

  public var loadState: StyleLoadState by mutableStateOf(StyleLoadState.Pending)
    internal set

  internal fun attach(owner: MapState) {
    this.owner = owner
  }

  internal fun setBaseStyleState(value: BaseStyle) {
    baseStyleState = value
  }
}

/** One temporary connection between a [MapState] and a map surface. */
public class MapPresentation
internal constructor(
  private val owner: MapState,
  internal val token: MapPresentationToken,
  internal val adapter: MapAdapter,
) {
  /** Whether this presentation is still the current connection. */
  public val isValid: Boolean
    get() = owner.isCurrent(this)
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
  private var closed = false

  internal val cameraState = CameraState(initialCameraPosition)
  internal val compatibilityStyleState = StyleState()

  public val style: MapStyleState = MapStyleState(initialBaseStyle).also { it.attach(this) }

  public val cameraPosition: CameraPosition
    get() = cameraState.position

  public var presentation: MapPresentation? by mutableStateOf(null)
    private set

  public val isClosed: Boolean
    get() = lock.withLock { closed }

  /** Marks this state as closed and starts cleanup of the current presentation. */
  public fun close() {
    val map = lock.withLock {
      if (closed) return
      closed = true
      val map = attachment?.adapter
      attachment = null
      Snapshot.withMutableSnapshot { presentation = null }
      map
    }
    cameraState.map = null
    if (map == null) {
      completeClosure(Result.success(Unit))
      return
    }
    map.close()
    runtime.physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      completeClosure(runCatching { map.awaitClosed() })
    }
  }

  /** Waits until presentation cleanup has completed. */
  public suspend fun awaitClosed() {
    closure.await().getOrThrow()
  }

  internal fun reservePresentation(): MapPresentationToken = lock.withLock {
    requireOpenLocked()
    check(attachment == null) { "The map state already has a presentation" }
    val token = MapPresentationToken(nextPresentationToken.incrementAndFetch())
    attachment = Attachment(token)
    token
  }

  internal fun publishPresentation(token: MapPresentationToken, adapter: MapAdapter) {
    lock.withLock {
      requireOpenLocked()
      val current = attachment
      check(current?.token == token && !current.releasing) {
        "The map presentation reservation is no longer current"
      }
      if (current.adapter === adapter) return
      check(current.adapter == null) { "The map state already has a presentation" }
      current.adapter = adapter
      MapPresentation(this, token, adapter).also { presentation = it }
      cameraState.map = adapter
      adapter.setBaseStyle(style.baseStyle)
      style.loadState = StyleLoadState.Loading
    }
  }

  internal fun releasePresentation(token: MapPresentationToken, adapter: MapAdapter? = null) {
    val closingAdapter = lock.withLock {
      val current = attachment ?: return
      if (current.token != token) return
      if (adapter != null && current.adapter !== adapter) return
      if (current.releasing) return
      current.releasing = true
      presentation = null
      style.loadState = StyleLoadState.Pending
      current.adapter
    }
    if (cameraState.map === closingAdapter) cameraState.map = null
    if (closingAdapter == null) {
      lock.withLock { if (attachment?.token == token) attachment = null }
      return
    }
    closingAdapter.close()
    runtime.physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      runCatching { closingAdapter.awaitClosed() }
      lock.withLock { if (attachment?.token == token) attachment = null }
    }
  }

  internal fun markStyleReady(adapter: MapAdapter) {
    lock.withLock {
      if (!closed && attachment?.adapter === adapter) style.loadState = StyleLoadState.Ready
    }
  }

  internal fun markStyleFailed(adapter: MapAdapter, reason: String?) {
    lock.withLock {
      if (!closed && attachment?.adapter === adapter) {
        style.loadState = StyleLoadState.Failed(reason)
      }
    }
  }

  internal fun setBaseStyle(value: BaseStyle) {
    lock.withLock {
      requireOpenLocked()
      if (style.baseStyle == value) return
      style.setBaseStyleState(value)
      val adapter = attachment?.adapter
      if (adapter == null) {
        style.loadState = StyleLoadState.Pending
      } else {
        style.loadState = StyleLoadState.Loading
        adapter.setBaseStyle(value)
      }
    }
  }

  internal fun isCurrent(candidate: MapPresentation): Boolean = lock.withLock {
    !closed && presentation === candidate && attachment?.token == candidate.token
  }

  private fun requireOpenLocked() {
    if (closed) throw MapStateClosedException()
  }

  private fun completeClosure(result: Result<Unit>) {
    if (closure.complete(result)) runtime.childClosed(this)
  }

  private class Attachment(
    val token: MapPresentationToken,
    var adapter: MapAdapter? = null,
    var releasing: Boolean = false,
  )

  private companion object {
    val nextPresentationToken = AtomicLong(0L)
  }
}

@JvmInline internal value class MapPresentationToken(val value: Long)

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
  internal val physicalScope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MapRuntime {
  private val lock = reentrantLock()
  private val children = linkedSetOf<MapState>()
  private val closure = CompletableDeferred<Result<Unit>>()
  private var closed = false

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
    get() = lock.withLock { closed }

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
  )
