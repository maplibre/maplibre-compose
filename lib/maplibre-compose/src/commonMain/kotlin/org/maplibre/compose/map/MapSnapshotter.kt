package org.maplibre.compose.map

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
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
import org.maplibre.compose.style.AnchorPredicateException
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleContent
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.style.checkStyleHandle
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.formatToString

/** Immutable inputs for one snapshot capture. */
public data class MapSnapshotRequest(
  /**
   * Size of the captured map. Both dimensions must be finite and positive.
   *
   * MapLibre lays out maps in whole dp, so each dimension is rounded to the nearest whole dp, and
   * to at least 1 dp. Each image dimension in pixels is the rounded size multiplied by [density],
   * rounded up.
   */
  public val size: DpSize,
  /** Camera position used for this capture. */
  public val cameraPosition: CameraPosition = CameraPosition(),
  /** Pixel density for rendering and font scale for style composition. */
  public val density: Density = Density(1f),
  /** Layout direction used while evaluating the style composition. */
  public val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  /** Whether to preserve framebuffer alpha. When false, transparent pixels composite onto white. */
  public val transparent: Boolean = false,
) {
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
public class MapSnapshotException internal constructor(message: String, cause: Throwable) :
  RuntimeException(message, cause)

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

  suspend fun capture(
    request: MapSnapshotRequest,
    revision: StyleSnapshot,
  ): ImageBitmap

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
  throw UnsupportedOperationException("Snapshot capture is not available on this platform")

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

/** An independent non-UI map that captures images. */
public sealed interface MapSnapshotter {
  /** Desired and applied style state for this snapshotter's engine map. */
  public val style: MapStyleState

  /**
   * Captures one image with [request]. Concurrent calls execute in submission order.
   *
   * Cancelling the caller removes a queued request or abandons an active result. After active
   * cancellation, the next request waits until platform rendering and terminal cleanup end.
   *
   * An exception thrown by the predicate of an [Anchor][org.maplibre.compose.layers.Anchor] in the
   * style content is thrown by this function unchanged.
   *
   * @throws IllegalStateException if the snapshotter is closed before this call.
   * @throws IllegalArgumentException if the request cannot be rendered on the current platform.
   * @throws UnsupportedOperationException if snapshots are unavailable on the current platform.
   * @throws CancellationException if the snapshotter closes after accepting this capture.
   * @throws MapSnapshotException if style evaluation or rendering fails.
   */
  public suspend fun capture(request: MapSnapshotRequest): ImageBitmap

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
      rejected = { target, error -> runtime.logger?.w(error) { "Could not $target" } },
    )
  }
  private var activeStyleClaim: StyleClaim? = null
  override val style: MapStyleState = MapStyleState(baseStyle).also { it.attach(this) }

  // Runs on the physical scope, not under a Mutex in the caller, so a canceled caller returns at
  // once while runQueue() holds the next capture until cleanup ends.
  override suspend fun capture(request: MapSnapshotRequest): ImageBitmap =
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
        capture.resumeFailure(error.toSnapshotAvailabilityFailure())
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
            // Drain accepted commands before entering Loading, which rejects further writes.
            // User composition and platform callbacks must run outside the command mutex.
            val currentClaim = resourceCommands.withCommit { claimStyle() }
            claim = currentClaim
            val prepared =
              platform.prepare(currentClaim.baseStyle, currentClaim.revision, capture.request)
            val currentBinding = prepared.binding
            binding = currentBinding
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
            resourceCommands.withCommit {
              if (style.currentLoadedStyle() === currentBinding)
                resourceCommands.requireNoConflicts(revision)
              recordStyleOwnership(currentClaim, revision)
            }
            val image = platform.capture(request, revision)
            resourceCommands.withCommit {
              if (!publishStyle(capture, currentClaim, currentBinding, revision)) {
                currentBinding.invalidate()
              }
            }
            Result.success(image)
          } catch (error: Throwable) {
            when (error) {
              is CancellationException -> binding?.invalidate()
              // A bug in the caller's code, thrown before any style content changed.
              is AnchorPredicateException -> claim?.let(::releaseStyleClaim)
              else -> claim?.let { publishStyleFailure(it, error) }
            }
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
      checkStyleHandle(!closed) { "The map snapshotter is closed" }
      if (style.baseStyle == value) return
      baseStyleRevision++
      resourceCommands.clear()
      style.setBaseStyleState(value)
      style.invalidateLoadedStyle()
      style.loadState = StyleLoadState.Pending
    }
  }

  /** Runs [mutate] on the map owner and reads the sources it leaves behind in the same task. */
  private suspend fun commitSourcesAfterCommand(binding: StyleBinding, mutate: () -> Unit) {
    val resources =
      binding.awaitOwner {
        mutate()
        style.readResources(binding)
      } ?: throw StyleHandleException("The loaded style changed before the command ran")
    lock.withLock {
      requireStyleHandleLocked(binding)
      style.updateResources(resources)
    }
  }

  override fun readyLoadedStyle(): StyleBinding? = lock.withLock {
    style.currentLoadedStyle()?.takeIf { style.loadState == StyleLoadState.Ready }
  }

  override fun <T> runStyleHandleOperation(
    binding: StyleBinding,
    action: () -> T,
  ): T {
    lock.withLock { requireStyleHandleLocked(binding) }
    val result = action()
    lock.withLock { requireStyleHandleLocked(binding) }
    return result
  }

  private fun claimStyle(): StyleClaim = lock.withLock {
    check(!closed) { "The map snapshotter is closed" }
    StyleClaim(
        baseStyle = style.baseStyle,
        revision = baseStyleRevision,
        ownership =
          if (ownedBaseStyleRevision == baseStyleRevision)
            SnapshotStyleOwnership(ownedSourceIds.toSet(), ownedLayerIds.toSet())
          else SnapshotStyleOwnership.Empty,
        loadState = style.loadState,
      )
      .also {
        check(activeStyleClaim == null)
        activeStyleClaim = it
        style.loadState = StyleLoadState.Loading
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
   * map owner while this snapshotter's lock is held. The style stays Loading until the handles are
   * in place.
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

  /** Returns the style to the state [claim] found, for a capture that changed no style content. */
  private fun releaseStyleClaim(claim: StyleClaim) {
    lock.withLock {
      if (!closed && claim.revision == baseStyleRevision) style.loadState = claim.loadState
    }
  }

  private fun completeStyleClaim(claim: StyleClaim) {
    lock.withLock {
      if (activeStyleClaim === claim) activeStyleClaim = null
    }
  }

  private fun requireStyleHandleLocked(binding: StyleBinding) {
    checkStyleHandle(!closed) { "The map snapshotter is closed" }
    style.requireReadyBinding(binding)
  }

  /** [loadState] is the state the claim replaced with Loading. */
  private data class StyleClaim(
    val baseStyle: BaseStyle,
    val revision: Long,
    val ownership: SnapshotStyleOwnership,
    val loadState: StyleLoadState,
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

private fun Throwable.toSnapshotAvailabilityFailure(): Throwable =
  if (this is UnsupportedOperationException) this else toSnapshotFailure()

private fun Throwable.toSnapshotRequestFailure(): Throwable =
  if (this is IllegalArgumentException) this else toSnapshotFailure()

private fun Throwable.toSnapshotFailure(): Throwable =
  when (this) {
    is AnchorPredicateException -> cause
    is CancellationException,
    is Error,
    is MapSnapshotException -> this
    else ->
      MapSnapshotException(
        message = "Snapshot capture failed: ${message ?: this::class.simpleName.orEmpty()}",
        cause = this,
      )
  }
