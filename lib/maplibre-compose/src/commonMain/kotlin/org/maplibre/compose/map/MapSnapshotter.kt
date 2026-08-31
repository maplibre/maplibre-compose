package org.maplibre.compose.map

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleComposition
import org.maplibre.compose.style.StyleContent
import org.maplibre.compose.style.StyleNode

/** Options that affect the pixels returned by a snapshot capture. */
public data class MapSnapshotOutputOptions(
  /** Whether to preserve framebuffer alpha. When false, transparent pixels composite onto white. */
  public val transparent: Boolean = false
)

/** Immutable inputs for one snapshot capture. */
public data class MapSnapshotRequest(
  /** Viewport width in logical pixels. */
  public val width: Int,
  /** Viewport height in logical pixels. */
  public val height: Int,
  /** Camera position used for this capture. */
  public val cameraPosition: CameraPosition = CameraPosition(),
  /** Density used for rendering and style evaluation. */
  public val density: Float = 1f,
  /** Font scale used while evaluating the style composition. */
  public val fontScale: Float = 1f,
  /** Layout direction used while evaluating the style composition. */
  public val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  /** Options that determine how captured pixels are returned. */
  public val outputOptions: MapSnapshotOutputOptions = MapSnapshotOutputOptions(),
) {
  init {
    require(width > 0) { "Snapshot width must be positive" }
    require(height > 0) { "Snapshot height must be positive" }
    require(density.isFinite() && density > 0f) { "Snapshot density must be finite and positive" }
    require(fontScale.isFinite() && fontScale > 0f) {
      "Snapshot font scale must be finite and positive"
    }
  }
}

/** Platform work for one snapshotter engine map. */
internal interface SnapshotterAdapter {
  suspend fun prepare(
    baseStyle: BaseStyle,
    baseStyleRevision: Long,
    request: MapSnapshotRequest,
  ): StyleBinding

  suspend fun capture(
    request: MapSnapshotRequest,
    revision: DesiredStyleRevision,
  ): ImageBitmap

  /** Requests cancellation and returns after the active platform operation has ended. */
  suspend fun cancelActiveCapture(): SnapshotterEngineDisposition

  suspend fun close()
}

/** Whether cancellation left the snapshotter engine and its loaded style available for reuse. */
internal enum class SnapshotterEngineDisposition {
  RETAINED,
  RELEASED,
}

internal fun interface SnapshotterAdapterFactory {
  fun create(): SnapshotterAdapter
}

internal object UnsupportedSnapshotterAdapterFactory : SnapshotterAdapterFactory {
  override fun create(): SnapshotterAdapter =
    throw UnsupportedOperationException("Snapshot capture is not available on this platform")
}

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
    composition: StyleComposition,
    style: StyleBinding,
    density: Density,
    layoutDirection: LayoutDirection,
    ownership: SnapshotStyleOwnership,
  ): DesiredStyleRevision
}

internal object DefaultStyleCompositionEvaluator : StyleCompositionEvaluator {
  override suspend fun evaluate(
    composition: StyleComposition,
    style: StyleBinding,
    density: Density,
    layoutDirection: LayoutDirection,
    ownership: SnapshotStyleOwnership,
  ): DesiredStyleRevision {
    val frameClock = BroadcastFrameClock()
    return withContext(frameClock) {
      coroutineScope {
        val revision = CompletableDeferred<DesiredStyleRevision>()
        val recomposer = Recomposer(currentCoroutineContext())
        val recomposerJob =
          launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
          }
        val root =
          StyleNode(
            style,
            replaceableSourceIds = ownership.sourceIds,
            replaceableLayerIds = ownership.layerIds,
          )
        val evaluator = Composition(MapNodeApplier(root), recomposer)
        try {
          evaluator.setContent {
            CompositionLocalProvider(
              LocalDensity provides density,
              LocalLayoutDirection provides layoutDirection,
            ) {
              StyleContent(
                rootNode = root,
                publish = revision::complete,
                content = composition.content,
              )
            }
          }
          while (!revision.isCompleted) {
            if (frameClock.hasAwaiters) frameClock.sendFrame(0L) else yield()
          }
          revision.await()
        } finally {
          evaluator.dispose()
          recomposer.close()
          recomposerJob.join()
        }
      }
    }
  }
}

/** Thrown when an operation targets a closed snapshotter. */
public class MapSnapshotterClosedException : IllegalStateException("The map snapshotter is closed")

/** An independent non-UI map that captures images. */
public interface MapSnapshotter {
  /** Desired and applied style state for this snapshotter's engine map. */
  public val style: MapStyleState

  /**
   * Captures one image with [request]. Concurrent calls execute in submission order.
   *
   * Cancelling the caller removes a queued request or abandons an active result. After active
   * cancellation, the next request waits until platform rendering and terminal cleanup end.
   *
   * @throws MapSnapshotterClosedException if this snapshotter closes before returning an image
   */
  public suspend fun capture(request: MapSnapshotRequest): ImageBitmap

  /**
   * Refuses new captures, clears queued captures, abandons an active result, and starts cleanup.
   * Call [awaitClosed] to wait for physical resources to be released.
   */
  public fun close()

  /**
   * Waits until this snapshotter has released its physical resources and reports cleanup errors.
   */
  public suspend fun awaitClosed()
}

internal class MapSnapshotterImplementation(
  private val runtime: RuntimeImplementation,
  initialBaseStyle: BaseStyle,
  private val styleComposition: StyleComposition,
  private val adapterFactory: SnapshotterAdapterFactory = runtime.snapshotterAdapterFactory,
  private val styleEvaluator: StyleCompositionEvaluator = runtime.styleEvaluator,
) : MapSnapshotter {
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
  private var styleHandleEpoch = 0L
  private var desiredRevision = DesiredStyleRevision.Empty

  override val style: MapStyleState =
    MapStyleState(initialBaseStyle).also {
      it.attach(
        object : MapStyleStateOwner {
          override fun setBaseStyle(value: BaseStyle) =
            this@MapSnapshotterImplementation.setBaseStyle(value)

          override fun desiredSourceDefinition(id: String) =
            this@MapSnapshotterImplementation.desiredSourceDefinition(id)

          override fun readyLoadedStyle() = this@MapSnapshotterImplementation.readyLoadedStyle()

          override fun <T> runStyleHandleOperation(
            binding: StyleBinding,
            action: () -> T,
          ): T = this@MapSnapshotterImplementation.runStyleHandleOperation(binding, action)

          override fun styleHandleCheckpoint(binding: StyleBinding) =
            this@MapSnapshotterImplementation.styleHandleCheckpoint(binding)

          override fun requireStyleHandleUnchanged(
            binding: StyleBinding,
            checkpoint: Long,
          ) =
            this@MapSnapshotterImplementation.requireStyleHandleUnchanged(
              binding,
              checkpoint,
            )
        }
      )
    }

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
      if (!accepted) continuation.resumeWithException(MapSnapshotterClosedException())
    }

  override fun close() {
    var cancellation: CompletableDeferred<Result<Unit>>? = null
    val finishNow = lock.withLock {
      if (closed) return
      closed = true
      val queued = queue.toList()
      queue.clear()
      queued.forEach { it.continuation.resumeWithException(MapSnapshotterClosedException()) }
      active?.also {
        cancellation = markCancellationLocked(it)
        it.abandon(MapSnapshotterClosedException())
        it.operation?.cancel()
      }
      active == null && worker == null
    }
    if (finishNow) runtime.physicalScope.launch { finishClose() }
    else cancellation?.let(::startActiveCancellation)
  }

  override suspend fun awaitClosed() {
    closure.await().getOrThrow()
  }

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
      val shouldClose = lock.withLock {
        active = null
        closed && queue.isEmpty()
      }
      if (shouldClose) break
    }

    if (lock.withLock { closed && active == null }) finishClose()
  }

  private suspend fun runCapture(capture: Capture) = coroutineScope {
    val platform =
      try {
        adapter ?: adapterFactory.create().also { adapter = it }
      } catch (error: Throwable) {
        capture.resumeFailure(error)
        return@coroutineScope
      }
    val operation =
      launch(start = CoroutineStart.LAZY) {
        var claim: StyleClaim? = null
        var binding: StyleBinding? = null
        try {
          val currentClaim = claimStyle()
          claim = currentClaim
          val currentBinding =
            platform.prepare(currentClaim.baseStyle, currentClaim.revision, capture.request)
          binding = currentBinding
          val request = capture.request
          val revision =
            styleEvaluator.evaluate(
              styleComposition,
              currentBinding,
              Density(request.density, request.fontScale),
              request.layoutDirection,
              currentClaim.ownership,
            )
          recordStyleOwnership(currentClaim, revision)
          val image = platform.capture(request, revision)
          if (!publishStyle(capture, currentClaim, currentBinding, revision)) {
            currentBinding.invalidate()
          }
          capture.resume(image)
        } catch (error: Throwable) {
          if (error is CancellationException) binding?.invalidate()
          else claim?.let { publishStyleFailure(it, error) }
          capture.resumeFailure(error)
        }
      }
    lock.withLock {
      capture.operation = operation
      if (capture.abandoned) operation.cancel()
    }
    operation.start()
    operation.join()
    capture.cancellation?.await()
  }

  private fun cancel(capture: Capture) {
    var cancellation: CompletableDeferred<Result<Unit>>? = null
    val wasQueued = lock.withLock {
      if (queue.remove(capture)) return@withLock true
      if (active !== capture) return@withLock false
      capture.abandoned = true
      cancellation = markCancellationLocked(capture)
      capture.operation?.cancel()
      false
    }
    if (!wasQueued) cancellation?.let(::startActiveCancellation)
  }

  /** Returns a new cleanup marker, or null when cleanup has already started. */
  private fun markCancellationLocked(capture: Capture): CompletableDeferred<Result<Unit>>? {
    if (capture.cancellation != null) return null
    return CompletableDeferred<Result<Unit>>().also { capture.cancellation = it }
  }

  private fun startActiveCancellation(cancellation: CompletableDeferred<Result<Unit>>) {
    runtime.physicalScope.launch {
      val result = runCatching {
        adapter?.cancelActiveCapture()
        settleStyleAfterCancellation()
        Unit
      }
      result.exceptionOrNull()?.let { error ->
        lock.withLock { if (cleanupFailures.none { it === error }) cleanupFailures += error }
      }
      cancellation.complete(result)
    }
  }

  private fun settleStyleAfterCancellation() {
    lock.withLock {
      styleHandleEpoch++
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
      adapter?.close()
      Unit
    }
    val styleResult = runCatching {
      style.invalidateLoadedStyle()
      Unit
    }
    val result = lock.withLock {
      val failures = cleanupFailures.toMutableList()
      closeResult.exceptionOrNull()?.let { error ->
        if (failures.none { it === error }) failures += error
      }
      styleResult.exceptionOrNull()?.let { error ->
        if (failures.none { it === error }) failures += error
      }
      when (failures.size) {
        0 -> Result.success(Unit)
        1 -> Result.failure(failures.single())
        else -> Result.failure(MapSnapshotterCleanupException(failures))
      }
    }
    runtime.childClosed(this)
    check(closure.complete(result)) { "Snapshotter closure completed more than once" }
  }

  internal fun setBaseStyle(value: BaseStyle) {
    lock.withLock {
      requireOpenLocked()
      if (style.baseStyle == value) return
      styleHandleEpoch++
      baseStyleRevision++
      style.setBaseStyleState(value)
      style.loadState = StyleLoadState.Pending
    }
  }

  internal fun desiredSourceDefinition(id: String): SourceDefinition? = lock.withLock {
    desiredRevision.sources.firstOrNull { it.id == id }
  }

  internal fun readyLoadedStyle(): StyleBinding? = lock.withLock {
    style.currentLoadedStyle()?.takeIf { style.loadState == StyleLoadState.Ready }
  }

  internal fun <T> runStyleHandleOperation(
    binding: StyleBinding,
    action: () -> T,
  ): T {
    lock.withLock { requireStyleHandleLocked(binding) }
    val result = action()
    lock.withLock { requireStyleHandleLocked(binding) }
    return result
  }

  internal fun styleHandleCheckpoint(binding: StyleBinding): Long = lock.withLock {
    requireStyleHandleLocked(binding)
    styleHandleEpoch
  }

  internal fun requireStyleHandleUnchanged(
    binding: StyleBinding,
    checkpoint: Long,
  ) {
    lock.withLock {
      requireStyleHandleLocked(binding)
      check(checkpoint == styleHandleEpoch) {
        "Style operation crossed a loaded-style resource change"
      }
    }
  }

  private fun claimStyle(): StyleClaim = lock.withLock {
    requireOpenLocked()
    style.loadState = StyleLoadState.Loading
    StyleClaim(
      baseStyle = style.baseStyle,
      revision = baseStyleRevision,
      ownership =
        if (ownedBaseStyleRevision == baseStyleRevision) {
          SnapshotStyleOwnership(ownedSourceIds.toSet(), ownedLayerIds.toSet())
        } else {
          SnapshotStyleOwnership.Empty
        },
    )
  }

  private fun recordStyleOwnership(claim: StyleClaim, revision: DesiredStyleRevision) {
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

  private fun publishStyle(
    capture: Capture,
    claim: StyleClaim,
    binding: StyleBinding,
    revision: DesiredStyleRevision,
  ): Boolean = lock.withLock {
    if (closed || capture.abandoned || claim.revision != baseStyleRevision) return@withLock false
    styleHandleEpoch++
    desiredRevision = revision
    style.updateLoadedStyle(binding)
    style.loadState = StyleLoadState.Ready
    style.refreshSources()
    true
  }

  private fun publishStyleFailure(claim: StyleClaim, error: Throwable) {
    lock.withLock {
      if (!closed && claim.revision == baseStyleRevision) {
        style.loadState = StyleLoadState.Failed(error.message)
      }
    }
  }

  private fun requireStyleHandleLocked(binding: StyleBinding) {
    requireOpenLocked()
    check(style.loadState == StyleLoadState.Ready && style.isCurrentLoadedStyle(binding)) {
      "Style operation belongs to a stale or unready loaded-style identity"
    }
  }

  private fun requireOpenLocked() {
    if (closed) throw MapSnapshotterClosedException()
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

    fun abandon(error: Throwable) {
      abandoned = true
      if (continuation.isActive) continuation.resumeWithException(error)
    }
  }
}

internal class MapSnapshotterCleanupException(val failures: List<Throwable>) :
  AggregateCleanupException(
    "Map snapshotter cleanup failed in ${failures.size} resource(s)",
    failures,
  )
