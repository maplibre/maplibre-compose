@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GroundScaleGlobalState
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleContent
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.style.groundScale
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.formatToString

/**
 * Immutable inputs for one snapshot capture.
 *
 * @property size Size of the captured map. Both dimensions must be finite and positive. Each
 *   dimension is rounded to the nearest whole dp, and to at least 1 dp. Each image dimension in
 *   pixels is the rounded size multiplied by [density], rounded up.
 * @property cameraPosition Camera position used for this capture.
 * @property density Pixel density for rendering and font scale for style composition.
 * @property layoutDirection Layout direction used while evaluating the style composition.
 * @property transparent Whether to preserve framebuffer alpha. When false, transparent pixels
 *   composite onto white.
 */
@Immutable
public data class MapSnapshotRequest
private constructor(
  public val size: DpSize,
  public val cameraPosition: CameraPosition,
  public val density: Density,
  public val layoutDirection: LayoutDirection,
  public val transparent: Boolean,
) {
  private constructor(
    size: DpSize,
    builder: Builder,
  ) : this(
    size,
    builder.cameraPosition,
    builder.density,
    builder.layoutDirection,
    builder.transparent,
  )

  /** Creates a request for [size] with the settings in [block]. */
  public constructor(
    size: DpSize,
    block: Builder.() -> Unit = {},
  ) : this(size, Builder().apply(block))

  init {
    require(size.width.value.isFinite() && size.width > 0.dp) {
      "Snapshot width must be finite and positive, was ${size.width}"
    }
    require(size.height.value.isFinite() && size.height > 0.dp) {
      "Snapshot height must be finite and positive, was ${size.height}"
    }
    require(density.density.isFinite() && density.density > 0f) {
      "Snapshot density must be finite and positive, was ${density.density}"
    }
    require(density.fontScale.isFinite() && density.fontScale > 0f) {
      "Snapshot font scale must be finite and positive, was ${density.fontScale}"
    }
  }

  @MapOptionsDsl
  public class Builder internal constructor() {
    /** See [MapSnapshotRequest.cameraPosition]. */
    public var cameraPosition: CameraPosition = CameraPosition()
    /** See [MapSnapshotRequest.density]. */
    public var density: Density = Density(1f)
    /** See [MapSnapshotRequest.layoutDirection]. */
    public var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    /** See [MapSnapshotRequest.transparent]. */
    public var transparent: Boolean = false
  }
}

/**
 * The engine map extent for this request: [MapSnapshotRequest.size] in whole logical pixels, at
 * [MapSnapshotRequest.density].
 */
internal fun MapSnapshotRequest.extent(): MapExtent =
  MapExtent.fromLogical(
    size.width.wholeLogicalPixels(),
    size.height.wholeLogicalPixels(),
    density.density.toDouble(),
  )

private fun Dp.wholeLogicalPixels(): Int = value.roundToInt().coerceAtLeast(1)

/** Reports a failed snapshot capture. */
public class MapSnapshotException internal constructor(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/**
 * A custom source provider's exception as the failure of a capture. A cancellation that the
 * provider caused itself, such as its own timeout, is wrapped so that it does not read as a
 * cancelled capture.
 */
internal fun Throwable.asProviderFailure(): Throwable =
  if (this is CancellationException) IllegalStateException(message, this) else this

/** Platform work for one snapshotter engine map. */
internal interface SnapshotterAdapter {
  fun validate(request: MapSnapshotRequest) = Unit

  /**
   * Applies the size and camera of [request] to the engine map and loads [baseStyle] if needed. The
   * returned viewport is read after both, so it describes the transform the capture renders,
   * including the loaded style's projection.
   */
  suspend fun prepare(
    baseStyle: BaseStyle,
    baseStyleRevision: Long,
    request: MapSnapshotRequest,
  ): SnapshotPreparation

  /**
   * Applies [revision] to the loaded style and waits for the outcome of its writes. Resource
   * commands wait meanwhile, as on a map.
   *
   * @return true if the engine rejected a source, layer, or move, so [revision] should be applied
   *   again.
   */
  suspend fun apply(revision: StyleSnapshot): Boolean

  /** Renders the loaded style with the revision that [apply] applied. */
  suspend fun capture(request: MapSnapshotRequest): ImageBitmap

  /** Requests cancellation and returns after the active platform operation has ended. */
  suspend fun cancelActiveCapture(): SnapshotterEngineDisposition

  suspend fun close()
}

/** The loaded style and the viewport of the request that [SnapshotterAdapter.prepare] applied. */
internal class SnapshotPreparation(val binding: StyleBinding, val viewport: Viewport)

/** Whether cancellation left the snapshotter engine and its loaded style available for reuse. */
internal enum class SnapshotterEngineDisposition {
  Retained,
  Released,
}

internal fun unsupportedSnapshots(): Nothing =
  throw MapSnapshotException("This map runtime does not render snapshots")

internal data class SnapshotStyleOwnership(
  val sourceIds: Set<String>,
  val layerIds: Set<String>,
) {
  companion object {
    val Empty = SnapshotStyleOwnership(emptySet(), emptySet())
  }
}

internal fun interface StyleCompositionEvaluator {
  suspend fun evaluate(
    content: @Composable @MaplibreComposable () -> Unit,
    style: StyleBinding,
    viewport: Viewport,
    density: Density,
    layoutDirection: LayoutDirection,
    ownership: SnapshotStyleOwnership,
  ): StyleSnapshot
}

internal object DefaultStyleCompositionEvaluator : StyleCompositionEvaluator {
  override suspend fun evaluate(
    content: @Composable @MaplibreComposable () -> Unit,
    style: StyleBinding,
    viewport: Viewport,
    density: Density,
    layoutDirection: LayoutDirection,
    ownership: SnapshotStyleOwnership,
  ): StyleSnapshot {
    val frameClock = BroadcastFrameClock()
    return withImageGraphicsContext { graphicsContext ->
      withContext(frameClock) {
        coroutineScope {
          val revision = CompletableDeferred<StyleSnapshot>()
          val recomposer = Recomposer(currentCoroutineContext())
          val recomposerJob =
            launch(start = CoroutineStart.UNDISPATCHED) {
              recomposer.runRecomposeAndApplyChanges()
            }
          val root =
            StyleNode(
              style,
              imageScope = this,
              replaceableSourceIds = ownership.sourceIds,
              replaceableLayerIds = ownership.layerIds,
              publish = { if (!it.imagesPending) revision.complete(it) },
            )
          val evaluator = Composition(MapNodeApplier(root), recomposer)
          try {
            evaluator.setContent {
              CompositionLocalProvider(
                LocalGraphicsContext provides graphicsContext,
                LocalDensity provides density,
                LocalLayoutDirection provides layoutDirection,
                LocalViewport provides viewport,
              ) {
                StyleContent(
                  rootNode = root,
                  content = content,
                )
              }
            }
            while (!revision.isCompleted) {
              // This evaluator has no UI host to deliver writes from painter preparation.
              Snapshot.sendApplyNotifications()
              if (frameClock.hasAwaiters) frameClock.sendFrame(0L) else yield()
            }
            revision.await()
          } finally {
            root.close()
            evaluator.dispose()
            recomposer.close()
            recomposerJob.join()
          }
        }
      }
    }
  }
}

/**
 * An independent non-UI map that captures images.
 *
 * An image contains only the rendered map: the sources, layers, and images of the style. It
 * contains no Compose UI, such as map controls, and no attribution text, so show the
 * [attributions][org.maplibre.compose.overlay.attributions] of [style] with the image.
 *
 * On Windows desktop, captures need the Vulkan runtime; with the OpenGL runtime, [capture] throws
 * [MapSnapshotException].
 */
public sealed interface MapSnapshotter {
  /**
   * Desired and applied style state for this snapshotter's engine map.
   *
   * A write during a capture that reuses the loaded style applies at once and may or may not appear
   * in that capture; a write while the style loads, such as during the first capture or the one
   * after a base style change, is skipped and logged.
   */
  public val style: MapStyleState

  /**
   * Captures one image at [size] with the settings in [block]. Concurrent calls execute in
   * submission order.
   *
   * Cancelling the caller removes a queued request or abandons an active result. After active
   * cancellation, the next request waits until platform rendering and terminal cleanup end.
   *
   * A capture is all or nothing: it fails if any tile or resource that it needs fails to load,
   * including a tile whose [GeometryTileProvider] or [VectorTileProvider] call fails. A tile that
   * does not exist, such as one answered with HTTP 404, has no data and does not fail the capture.
   * On the browser, MapLibre draws text whose glyphs fail to load with a local font instead.
   *
   * @throws IllegalStateException if the snapshotter is closed before this call.
   * @throws IllegalArgumentException if the request cannot be rendered on the current platform,
   *   such as a request whose size times its density, rounded to whole pixels, is more than 4,096
   *   in either dimension on the browser, which is the MapLibre GL JS canvas limit.
   * @throws CancellationException if the snapshotter closes after accepting this capture.
   * @throws MapSnapshotException if the runtime cannot render offscreen, such as when MapLibre
   *   Native offers no offscreen rendering backend for the device, style evaluation or rendering
   *   fails, or a tile or resource fails to load. When a [GeometryTileProvider] or
   *   [VectorTileProvider] call failed, the cause is its exception, wrapped in an
   *   [IllegalStateException] when it is a cancellation that the provider caused itself.
   */
  public suspend fun capture(
    size: DpSize,
    block: MapSnapshotRequest.Builder.() -> Unit = {},
  ): ImageBitmap

  /**
   * Refuses new captures, clears queued captures, abandons an active result, and starts cleanup.
   * Call [awaitClosed] to wait for physical resources to be released.
   */
  public fun close()

  /**
   * Waits until this snapshotter has released its physical resources and reports cleanup errors.
   *
   * @throws MapCleanupException if cleanup fails.
   */
  public suspend fun awaitClosed()
}

internal class MapSnapshotterImplementation(
  private val runtime: MapRuntime,
  baseStyle: BaseStyle,
  private val styleContent: @Composable @MaplibreComposable () -> Unit,
) : MapSnapshotter, MapStyleStateOwner {
  private val mutex = Mutex()
  private val captures = SupervisorJob(runtime.physicalScope.coroutineContext[Job])
  private val captureScope =
    CoroutineScope(runtime.physicalScope.coroutineContext + captures + runtime.mainDispatcher)
  private val closure = CompletableDeferred<Result<Unit>>()
  private var adapter: SnapshotterAdapter? = null
  private var active: Job? = null
  private var cleanup: Deferred<Unit>? = null
  private val cleanupFailures = mutableListOf<Throwable>()
  private val closeRequested = AtomicBoolean(false)
  private val closed: Boolean
    get() = closeRequested.load()

  private var baseStyleRevision = 0L
  private var ownedBaseStyleRevision: Long? = null
  private val ownedSourceIds = mutableSetOf<String>()
  private val ownedLayerIds = mutableSetOf<String>()
  override val resourceCommands by lazy { StyleResourceCommands(style) }

  override val logger: MapLog?
    get() = runtime.logger

  override val style: MapStyleState = MapStyleState(baseStyle).also { it.attach(this) }

  override suspend fun capture(
    size: DpSize,
    block: MapSnapshotRequest.Builder.() -> Unit,
  ): ImageBitmap {
    val request = MapSnapshotRequest(size, block)
    check(!closed) { "The map snapshotter is closed" }
    return withContext(runtime.mainDispatcher) {
      val operation = captureScope.async {
        mutex.withLock {
          cleanup?.await()
          currentCoroutineContext().ensureActive()
          active = currentCoroutineContext().job
          runCapture(request)
        }
      }
      try {
        operation.await()
      } catch (error: CancellationException) {
        cancelActive(operation)
        throw error
      } finally {
        if (active === operation) active = null
        operation.cancel()
      }
    }
  }

  private fun cancelActive(operation: Job) {
    if (active !== operation) return
    active = null
    // Register cleanup before cancellation can release the Mutex to its next waiter.
    cleanup =
      runtime.physicalScope.async(runtime.mainDispatcher, start = CoroutineStart.LAZY) {
        runCatching {
            // Platform cancellation can unblock an operation that is still finishing.
            adapter?.cancelActiveCapture()
            operation.join()
            resourceCommands.clear()
            style.invalidateLoadedStyle()
            if (!closed) style.loadState = StyleLoadState.Pending
          }
          .exceptionOrNull()
          ?.let(cleanupFailures::addCleanupFailure)
      }
    operation.cancel()
    cleanup?.start()
  }

  override fun close() {
    if (!closeRequested.compareAndSet(expectedValue = false, newValue = true)) return
    runtime.physicalScope.launch(runtime.mainDispatcher) {
      active?.let(::cancelActive)
      captures.cancel(snapshotterClosedCancellation())
      captures.join()
      cleanup?.await()
      finishClose()
    }
  }

  override suspend fun awaitClosed() {
    closure.await().getOrThrow()
  }

  override fun toString(): String =
    formatToString("MapSnapshotter", "closed" to closed, "style" to style)

  private suspend fun runCapture(request: MapSnapshotRequest): ImageBitmap {
    val platform =
      try {
        adapter ?: runtime.createSnapshotterAdapter().also { adapter = it }
      } catch (error: Throwable) {
        throw error.toSnapshotFailure()
      }
    try {
      platform.validate(request)
    } catch (error: Throwable) {
      throw error.toSnapshotRequestFailure()
    }
    val claim = claimStyle()
    var binding: StyleBinding? = null
    try {
      val prepared = platform.prepare(claim.baseStyle, claim.revision, request)
      val currentBinding = prepared.binding
      binding = currentBinding
      markReloaded(currentBinding)
      val revision =
        runtime.styleEvaluator.evaluate(
          styleContent,
          currentBinding,
          prepared.viewport,
          request.density,
          request.layoutDirection,
          styleEvaluationOwnership(currentBinding, claim.ownership),
        )
      val groundScale = JsonPrimitive(groundScale(prepared.viewport.cameraPosition.center.latitude))
      declareRevision(currentBinding, revision)
      recordStyleOwnership(claim, revision)
      currentBinding.setGlobalStateProperty(GroundScaleGlobalState, groundScale)
      // Applied again after a rejection: the rejected definitions stay out, and what they had
      // blocked goes in.
      for (pass in 1..MaxStyleRevisionPasses) {
        if (!platform.apply(revision)) break
        currentCoroutineContext().ensureActive()
      }
      // Publish a reused style's handles before rendering, as a map does.
      if (isCurrent(currentBinding)) style.updateResources(style.resources(currentBinding))
      val image = platform.capture(request)
      currentCoroutineContext().ensureActive()
      if (!publishStyle(claim, currentBinding, revision)) currentBinding.invalidate()
      return image
    } catch (error: Throwable) {
      if (error is CancellationException) {
        binding?.takeUnless { style.isCurrentLoadedStyle(it) }?.invalidate()
      } else publishStyleFailure(claim, error)
      throw error.toSnapshotFailure()
    }
  }

  private suspend fun finishClose() {
    runCatching { adapter?.close() }.exceptionOrNull()?.let(cleanupFailures::addCleanupFailure)
    runCatching { style.invalidateLoadedStyle() }
      .exceptionOrNull()
      ?.let(cleanupFailures::addCleanupFailure)
    runtime.childClosed(this)
    closure.complete(cleanupFailures.cleanupResult("Map snapshotter"))
  }

  override fun setBaseStyle(value: BaseStyle) {
    requireOpen()
    if (style.baseStyle == value) return
    baseStyleRevision++
    resourceCommands.clear()
    style.setBaseStyleState(value)
    style.invalidateLoadedStyle()
    style.loadState = StyleLoadState.Pending
  }

  override fun requireOpen() {
    runtime.mainThread.requireMain()
    check(!closed) { "The map snapshotter is closed" }
  }

  override fun readyLoadedStyle(): StyleBinding? =
    style.currentLoadedStyle()?.takeIf { style.loadState == StyleLoadState.Ready }

  // A closed snapshotter keeps its loaded style until cleanup finishes, so closure is checked too.
  override fun isCurrent(binding: StyleBinding): Boolean = !closed && style.isReadyBinding(binding)

  private fun claimStyle(): StyleClaim {
    if (closed) throw snapshotterClosedCancellation()
    // A ready style is reused, so it stays writable while the capture runs.
    if (style.loadState != StyleLoadState.Ready) style.loadState = StyleLoadState.Loading
    return StyleClaim(
      baseStyle = style.baseStyle,
      revision = baseStyleRevision,
      ownership =
        if (ownedBaseStyleRevision == baseStyleRevision)
          SnapshotStyleOwnership(ownedSourceIds.toSet(), ownedLayerIds.toSet())
        else SnapshotStyleOwnership.Empty,
    )
  }

  /** A ready style that the adapter replaced anyway, such as for a new density, is loading. */
  private fun markReloaded(binding: StyleBinding) {
    if (!closed && style.loadState == StyleLoadState.Ready && !style.isCurrentLoadedStyle(binding))
      style.loadState = StyleLoadState.Loading
  }

  /** Declares [revision] for the reused [binding] before applying it, as a map does. */
  private fun declareRevision(binding: StyleBinding, revision: StyleSnapshot) {
    if (!style.isCurrentLoadedStyle(binding)) return
    resourceCommands.requireNoConflicts(revision)
    style.declaredRevision = revision
  }

  private fun styleEvaluationOwnership(
    binding: StyleBinding,
    ownership: SnapshotStyleOwnership,
  ): SnapshotStyleOwnership {
    if (style.currentLoadedStyle() !== binding) return ownership
    return ownership.copy(sourceIds = ownership.sourceIds + resourceCommands.sourceIds())
  }

  private fun recordStyleOwnership(claim: StyleClaim, revision: StyleSnapshot) {
    if (closed || claim.revision != baseStyleRevision) return
    if (ownedBaseStyleRevision != claim.revision) {
      ownedSourceIds.clear()
      ownedLayerIds.clear()
      ownedBaseStyleRevision = claim.revision
    }
    revision.sources.mapTo(ownedSourceIds) { it.id }
    revision.layers.mapTo(ownedLayerIds) { it.definition.id }
  }

  /** Publishes [binding] with the handles of the resources it holds after [revision]. */
  private fun publishStyle(
    claim: StyleClaim,
    binding: StyleBinding,
    revision: StyleSnapshot,
  ): Boolean {
    if (closed || claim.revision != baseStyleRevision) return false
    if (style.currentLoadedStyle() !== binding) {
      resourceCommands.clear()
      style.updateLoadedStyle(binding)
    }
    // The handles read source options from the desired revision, so that is committed first.
    style.declaredRevision = revision
    style.updateResources(style.resources(binding))
    style.loadState = StyleLoadState.Ready
    return true
  }

  private fun publishStyleFailure(claim: StyleClaim, error: Throwable) {
    if (!closed && claim.revision == baseStyleRevision) {
      style.loadState = StyleLoadState.Failed(error.message)
    }
  }

  private data class StyleClaim(
    val baseStyle: BaseStyle,
    val revision: Long,
    val ownership: SnapshotStyleOwnership,
  )
}

internal fun snapshotterClosedCancellation(): CancellationException =
  CancellationException("The map snapshotter closed during capture")

private fun Throwable.toSnapshotRequestFailure(): Throwable =
  if (this is IllegalArgumentException) this else toSnapshotFailure()

private fun Throwable.toSnapshotFailure(): Throwable =
  when (this) {
    is CancellationException,
    is Error,
    is MapSnapshotException -> this
    else ->
      MapSnapshotException(
        message = "Snapshot capture failed: ${message ?: this::class.simpleName.orEmpty()}",
        cause = this,
      )
  }
