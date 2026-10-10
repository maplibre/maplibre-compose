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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleContent
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.StyleSnapshot
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

  /** Applies [revision] to the loaded style. Resource commands wait meanwhile, as on a map. */
  suspend fun apply(revision: StyleSnapshot)

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
  private val lock = reentrantLock()
  private val queue = ArrayDeque<Capture>()
  private val closure = CompletableDeferred<Result<Unit>>()
  private var adapter: SnapshotterAdapter? = null
  private var active: Capture? = null
  private var worker: Job? = null
  private val cleanupFailures = mutableListOf<Throwable>()
  private var closed = false
  private var finishing = false
  private var baseStyleRevision = 0L
  private var ownedBaseStyleRevision: Long? = null
  private val ownedSourceIds = mutableSetOf<String>()
  private val ownedLayerIds = mutableSetOf<String>()
  override val resourceCommands by lazy {
    StyleResourceCommands(
      style,
      runtime.physicalScope,
      commitSources = { binding, mutate -> commitSourcesAfterCommand(binding, mutate) },
    )
  }

  override val logger: MapLog?
    get() = runtime.logger

  private var activeStyleClaim: StyleClaim? = null
  override val style: MapStyleState = MapStyleState(baseStyle).also { it.attach(this) }

  // Runs on the physical scope, not under a Mutex in the caller, so a canceled caller returns at
  // once while runQueue() holds the next capture until cleanup ends.
  override suspend fun capture(
    size: DpSize,
    block: MapSnapshotRequest.Builder.() -> Unit,
  ): ImageBitmap = captureRequest(MapSnapshotRequest(size, block))

  private suspend fun captureRequest(request: MapSnapshotRequest): ImageBitmap =
    suspendCancellableCoroutine { continuation ->
      val capture = Capture(request, continuation)
      val accepted = lock.withLock {
        if (closed) return@withLock false
        queue.addLast(capture)
        continuation.invokeOnCancellation { cancel(capture) }
        if (worker == null) worker = runtime.physicalScope.launch { runQueue() }
        true
      }
      if (!accepted) {
        continuation.resumeWithException(IllegalStateException("The map snapshotter is closed"))
      }
    }

  override fun close() {
    val (finishNow, cancellation) =
      lock.withLock {
        if (closed) return
        closed = true
        val queued = queue.toList()
        queue.clear()
        queued.forEach { it.continuation.resumeWithException(snapshotterClosedCancellation()) }
        val cancellation = active?.let { abandonActiveLocked(it, snapshotterClosedCancellation()) }
        (active == null && worker == null) to cancellation
      }
    if (finishNow) runtime.physicalScope.launch { finishClose() }
    cancellation?.let(::startActiveCancellation)
  }

  override suspend fun awaitClosed() {
    closure.await().getOrThrow()
  }

  override fun toString(): String =
    formatToString("MapSnapshotter", "closed" to lock.withLock { closed }, "style" to style)

  private suspend fun runQueue() {
    while (true) {
      val next =
        lock.withLock {
          val candidate = queue.removeFirstOrNull()
          if (candidate == null) {
            worker = null
            null
          } else {
            active = candidate
            candidate
          }
        } ?: break

      runCapture(next)
      // close() and cancel() attach a cancellation only while this capture is active, so reading
      // it in the section that clears `active` sees every marker. Awaiting it before finishClose()
      // keeps the cancellation's cleanup failures inside the closure result.
      val (cancellation, shouldClose) =
        lock.withLock {
          active = null
          next.cancellation to (closed && queue.isEmpty())
        }
      cancellation?.await()
      if (shouldClose) break
    }

    if (lock.withLock { closed && active == null }) finishClose()
  }

  private suspend fun runCapture(capture: Capture) = coroutineScope {
    val platform =
      try {
        adapter ?: runtime.createSnapshotterAdapter().also { adapter = it }
      } catch (error: Throwable) {
        capture.resumeFailure(error.toSnapshotFailure())
        return@coroutineScope
      }
    val operation =
      launch(start = CoroutineStart.LAZY) {
        try {
          platform.validate(capture.request)
        } catch (error: Throwable) {
          capture.resumeFailure(error.toSnapshotRequestFailure())
          return@launch
        }
        var claim: StyleClaim? = null
        var binding: StyleBinding? = null
        val result =
          try {
            // Drain accepted commands before a load enters Loading, which rejects further writes.
            // User composition and platform callbacks must run outside the command mutex.
            val currentClaim = resourceCommands.withCommit { claimStyle() }
            claim = currentClaim
            val prepared =
              platform.prepare(currentClaim.baseStyle, currentClaim.revision, capture.request)
            val currentBinding = prepared.binding
            binding = currentBinding
            markReloaded(currentBinding)
            val request = capture.request
            val evaluationOwnership =
              styleEvaluationOwnership(currentBinding, currentClaim.ownership)
            val revision =
              runtime.styleEvaluator.evaluate(
                styleContent,
                currentBinding,
                prepared.viewport,
                request.density,
                request.layoutDirection,
                evaluationOwnership,
              )
            // A command sees the revision before or after this, never part of it.
            resourceCommands.withCommit {
              declareRevision(currentBinding, revision)
              recordStyleOwnership(currentClaim, revision)
              platform.apply(revision)
              // Publish a reused style's handles before rendering, as a map does.
              commitSourcesAfterCommand(currentBinding) {}
            }
            val image = platform.capture(request)
            resourceCommands.withCommit {
              if (!publishStyle(capture, currentClaim, currentBinding, revision)) {
                currentBinding.invalidate()
              }
            }
            Result.success(image)
          } catch (error: Throwable) {
            if (error is CancellationException) binding?.invalidate()
            else claim?.let { publishStyleFailure(it, error) }
            Result.failure(error)
          } finally {
            claim?.let(::completeStyleClaim)
          }
        result.fold(capture::resume) { capture.resumeFailure(it.toSnapshotFailure()) }
      }
    lock.withLock {
      capture.operation = operation
      if (capture.abandoned) operation.cancel()
    }
    operation.start()
    operation.join()
  }

  private fun cancel(capture: Capture) {
    val cancellation = lock.withLock {
      if (queue.remove(capture) || active !== capture) return
      abandonActiveLocked(capture)
    }
    cancellation?.let(::startActiveCancellation)
  }

  /** Returns a new cleanup marker, or null when cleanup has already started. */
  private fun abandonActiveLocked(
    capture: Capture,
    error: Throwable? = null,
  ): CompletableDeferred<Result<Unit>>? {
    val cancellation =
      if (capture.cancellation == null) {
        CompletableDeferred<Result<Unit>>().also { capture.cancellation = it }
      } else null
    capture.abandon(error)
    capture.operation?.cancel()
    return cancellation
  }

  private fun startActiveCancellation(cancellation: CompletableDeferred<Result<Unit>>) {
    runtime.physicalScope.launch {
      val result = runCatching {
        adapter?.cancelActiveCapture()
        settleStyleAfterCancellation()
        Unit
      }
      result.exceptionOrNull()?.let { error ->
        lock.withLock { cleanupFailures.addCleanupFailure(error) }
      }
      cancellation.complete(result)
    }
  }

  private fun settleStyleAfterCancellation() {
    lock.withLock {
      resourceCommands.clear()
      style.invalidateLoadedStyle()
      if (!closed) style.loadState = StyleLoadState.Pending
    }
  }

  private suspend fun finishClose() {
    val shouldFinish = lock.withLock {
      if (finishing) false
      else {
        finishing = true
        true
      }
    }
    if (!shouldFinish) {
      closure.await()
      return
    }
    val closeResult = runCatching {
      resourceCommands.await()
      adapter?.close()
      Unit
    }
    val styleResult = runCatching {
      style.invalidateLoadedStyle()
      Unit
    }
    val result = lock.withLock {
      val failures = cleanupFailures.toMutableList()
      closeResult.exceptionOrNull()?.let(failures::addCleanupFailure)
      styleResult.exceptionOrNull()?.let(failures::addCleanupFailure)
      failures.cleanupResult("Map snapshotter")
    }
    runtime.childClosed(this)
    check(closure.complete(result)) { "Snapshotter closure completed more than once" }
  }

  override fun setBaseStyle(value: BaseStyle) {
    lock.withLock {
      check(!closed) { "The map snapshotter is closed" }
      if (style.baseStyle == value) return
      baseStyleRevision++
      resourceCommands.clear()
      style.setBaseStyleState(value)
      style.invalidateLoadedStyle()
      style.loadState = StyleLoadState.Pending
    }
  }

  /**
   * Runs [mutate] on the map owner and reads the sources it leaves behind in the same task.
   *
   * @return false, with nothing published, when the loaded style changed first.
   */
  private suspend fun commitSourcesAfterCommand(
    binding: StyleBinding,
    mutate: () -> Unit,
  ): Boolean {
    val resources =
      style.visit(binding) {
        mutate()
        style.readResources(binding)
      } ?: return false
    return lock.withLock {
      if (!isCurrentLocked(binding)) return@withLock false
      style.updateResources(resources)
      true
    }
  }

  override fun requireOpen() {
    lock.withLock { check(!closed) { "The map snapshotter is closed" } }
  }

  override fun readyLoadedStyle(): StyleBinding? = lock.withLock {
    style.currentLoadedStyle()?.takeIf { style.loadState == StyleLoadState.Ready }
  }

  // A closed snapshotter keeps its loaded style until cleanup finishes, so closure is checked too.
  override fun isCurrent(binding: StyleBinding): Boolean = lock.withLock {
    isCurrentLocked(binding)
  }

  private fun isCurrentLocked(binding: StyleBinding): Boolean =
    !closed && style.isReadyBinding(binding)

  private fun claimStyle(): StyleClaim = lock.withLock {
    check(!closed) { "The map snapshotter is closed" }
    StyleClaim(
        baseStyle = style.baseStyle,
        revision = baseStyleRevision,
        ownership =
          if (ownedBaseStyleRevision == baseStyleRevision)
            SnapshotStyleOwnership(ownedSourceIds.toSet(), ownedLayerIds.toSet())
          else SnapshotStyleOwnership.Empty,
      )
      .also {
        check(activeStyleClaim == null)
        activeStyleClaim = it
        // A ready style is reused, so it stays writable while the capture runs.
        if (style.loadState != StyleLoadState.Ready) style.loadState = StyleLoadState.Loading
      }
  }

  /** A ready style that the adapter replaced anyway, such as for a new density, is loading. */
  private fun markReloaded(binding: StyleBinding) {
    lock.withLock {
      if (
        !closed && style.loadState == StyleLoadState.Ready && !style.isCurrentLoadedStyle(binding)
      )
        style.loadState = StyleLoadState.Loading
    }
  }

  /** Declares [revision] for the reused [binding] before applying it, as a map does. */
  private fun declareRevision(binding: StyleBinding, revision: StyleSnapshot) {
    lock.withLock {
      if (!style.isCurrentLoadedStyle(binding)) return
      resourceCommands.requireNoConflicts(revision)
      style.declaredRevision = revision
    }
  }

  private fun styleEvaluationOwnership(
    binding: StyleBinding,
    ownership: SnapshotStyleOwnership,
  ): SnapshotStyleOwnership = lock.withLock {
    if (style.currentLoadedStyle() !== binding) return ownership
    ownership.copy(sourceIds = ownership.sourceIds + resourceCommands.sourceIds())
  }

  private fun recordStyleOwnership(claim: StyleClaim, revision: StyleSnapshot) {
    lock.withLock {
      if (closed || claim.revision != baseStyleRevision) return
      if (ownedBaseStyleRevision != claim.revision) {
        ownedSourceIds.clear()
        ownedLayerIds.clear()
        ownedBaseStyleRevision = claim.revision
      }
      revision.sources.mapTo(ownedSourceIds) { it.id }
      revision.layers.mapTo(ownedLayerIds) { it.definition.id }
    }
  }

  /**
   * Publishes [binding] with the handles of the resources it holds after [revision]. The engine
   * read runs as an owner task between two locked steps, so a UI-thread lookup never waits on the
   * map owner while this snapshotter's lock is held. A loading style stays Loading until the
   * handles are in place.
   */
  private suspend fun publishStyle(
    capture: Capture,
    claim: StyleClaim,
    binding: StyleBinding,
    revision: StyleSnapshot,
  ): Boolean {
    // The read captures metadata from the desired revision, so that is committed first.
    val accepted = lock.withLock {
      if (closed || capture.abandoned || claim.revision != baseStyleRevision) {
        return@withLock false
      }
      if (style.currentLoadedStyle() !== binding) {
        resourceCommands.clear()
        style.updateLoadedStyle(binding)
      }
      style.declaredRevision = revision
      true
    }
    if (!accepted) return false
    val resources = binding.awaitOwner { style.readResources(binding) } ?: return false
    return lock.withLock {
      if (
        closed ||
          capture.abandoned ||
          claim.revision != baseStyleRevision ||
          style.currentLoadedStyle() !== binding
      ) {
        return@withLock false
      }
      style.updateResources(resources)
      style.loadState = StyleLoadState.Ready
      true
    }
  }

  private fun publishStyleFailure(claim: StyleClaim, error: Throwable) {
    lock.withLock {
      if (!closed && claim.revision == baseStyleRevision) {
        style.loadState = StyleLoadState.Failed(error.message)
      }
    }
  }

  private fun completeStyleClaim(claim: StyleClaim) {
    lock.withLock {
      if (activeStyleClaim === claim) activeStyleClaim = null
    }
  }

  private data class StyleClaim(
    val baseStyle: BaseStyle,
    val revision: Long,
    val ownership: SnapshotStyleOwnership,
  )

  private class Capture(
    val request: MapSnapshotRequest,
    val continuation: CancellableContinuation<ImageBitmap>,
  ) {
    var abandoned = false
    var operation: Job? = null
    var cancellation: CompletableDeferred<Result<Unit>>? = null

    fun resume(image: ImageBitmap) {
      if (!abandoned && continuation.isActive) continuation.resume(image)
    }

    fun resumeFailure(error: Throwable) {
      if (!abandoned && continuation.isActive) continuation.resumeWithException(error)
    }

    fun abandon(error: Throwable?) {
      abandoned = true
      if (error != null && continuation.isActive) continuation.resumeWithException(error)
    }
  }
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
