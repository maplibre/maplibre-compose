@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.style

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.isVirtualTestDispatcher
import org.maplibre.compose.map.runBlockingOn
import org.maplibre.compose.util.rethrowIfFatal

/**
 * Owns one style [Composition], its [Recomposer], and the frame clock that drives it, with no
 * hosting UI composition.
 *
 * The host pumps its own frames: [BroadcastFrameClock.onNewAwaiters] fires when the recomposer
 * needs a frame, and the frame-pump coroutine answers it on [dispatcher], pacing back-to-back
 * requests to a display frame interval so time-based animations play in real time. A global
 * snapshot write observer replaces the platform's GlobalSnapshotManager so plain snapshot writes
 * reach the recomposer even when no UI composition is running.
 *
 * The host runs on [dispatcher], the platform's UI dispatcher in production. UI components'
 * snapshot observers assume that thread, so composing and delivering apply notifications there
 * keeps every snapshot publication single-threaded. [setContent] marshals onto it, and the host
 * applies a [DesiredStyleRevision] after the initial composition and after each frame; sources
 * still attach before layers because effects run inside the frame and this flush runs after it.
 */
internal class StyleCompositionHost(
  private val rootNode: StyleNode,
  private val uiDispatcher: CoroutineDispatcher,
  density: Density,
  layoutDirection: LayoutDirection,
  // Mutable so a host constructed before the composable's logger is known picks it up.
  var logger: Logger?,
  // Null only in tests that compose a style with no owning map.
  private val mapState: MapState? = null,
) : AutoCloseable {

  /** The density the content reads through [LocalDensity]; snapshot-backed so writes recompose. */
  var density: Density by mutableStateOf(density)

  /** The layout direction the content reads through [LocalLayoutDirection]. */
  var layoutDirection: LayoutDirection by mutableStateOf(layoutDirection)

  /**
   * The UI composition's locals, re-provided around the content so values such as a theme survive
   * the host boundary; null when no UI composition supplies them.
   */
  var inheritedLocals: CompositionLocalContext? by mutableStateOf(null)

  private val frameSignal = Channel<Unit>(Channel.CONFLATED)
  private val snapshotSignal = Channel<Unit>(Channel.CONFLATED)

  // A frame is pending from its request until the pump's sync after it; awaitPendingWork counts
  // it as pending work. Counters instead of a flag: the conflated signal coalesces requests, and
  // a request arriving mid-frame must stay pending past that frame's completion.
  private val requestedFrames = AtomicLong(0L)
  private val completedFrames = AtomicLong(0L)

  private val frameIsPending: Boolean
    get() = requestedFrames.load() > completedFrames.load()

  /**
   * The constructor's dispatcher behind a mandatory queue hop. The write observer fires while the
   * global snapshot lock is held, and a UI dispatcher that runs on-thread resumes inline would run
   * the apply — and its blocking owner-thread round-trips — under that lock, deadlocking against
   * the owner thread's own snapshot writes.
   */
  private val dispatcher: CoroutineDispatcher = QueueingDispatcher(uiDispatcher)

  private val clock = BroadcastFrameClock {
    requestedFrames.incrementAndFetch()
    frameSignal.trySend(Unit)
  }
  private val job = Job()
  private val exceptionHandler = CoroutineExceptionHandler { _, error ->
    logger?.e(error) { "Uncaught error on the style composition host" }
  }
  private val scope = CoroutineScope(job + dispatcher + clock + exceptionHandler)

  private val serialized = Mutex()

  internal val recomposer = Recomposer(scope.coroutineContext)

  // Host-thread confined; every access rides a coroutine on the single-threaded dispatcher.
  private var composition: Composition? = null

  /** True before the first [setContent] and after each disposal; there is nothing to sync then. */
  private var disposed = true

  private var closed = false

  /** Non-null when the recomposition loop or a content composition died. */
  internal var contentError: Throwable? = null
    private set

  /** Sized so a burst of failures between collections is kept rather than suspended on. */
  private val errors = MutableSharedFlow<StyleError>(extraBufferCapacity = 16)

  /** Backs `MapState.styleErrors`; every ordinary failure the host records is emitted here. */
  internal val styleErrors: SharedFlow<StyleError> = errors.asSharedFlow()

  /** Records, publishes, and logs an ordinary failure; tryEmit never blocks the host thread. */
  private fun reportError(message: String, error: Throwable) {
    contentError = error
    errors.tryEmit(StyleError(message, error))
    logger?.e(error) { message }
  }

  private fun closedWhileSettling(): IllegalStateException =
    IllegalStateException("MapState was closed while the style composition was settling")

  private val writeObserver = Snapshot.registerGlobalWriteObserver { snapshotSignal.trySend(Unit) }

  init {
    rootNode.requestSync = ::requestApplyChanges
    // Emits without touching contentError, because a reported sync condition is not a dead style.
    rootNode.reportError = { error ->
      errors.tryEmit(error)
      logger?.e(error.cause) { error.message }
    }
    scope.launch {
      try {
        recomposer.runRecomposeAndApplyChanges()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        rethrowIfFatal(error)
        reportError("Style composition failed; the style stops updating", error)
      }
    }
    scope.launch { for (unused in snapshotSignal) Snapshot.sendApplyNotifications() }
    scope.launch {
      val clockStart = TimeSource.Monotonic.markNow()
      var lastFrameNanos = Long.MIN_VALUE
      for (unused in frameSignal) {
        val epoch = requestedFrames.load()
        // A request arriving within a frame interval of the previous frame waits out the rest.
        if (lastFrameNanos != Long.MIN_VALUE) {
          val sinceLast = clockStart.elapsedNow().inWholeNanoseconds - lastFrameNanos
          if (sinceLast < FRAME_INTERVAL_NANOS)
            delay((FRAME_INTERVAL_NANOS - sinceLast).nanoseconds)
        }
        // The bump keeps timestamps strictly increasing when a virtual-time delay skips real time.
        val frameNanos = maxOf(clockStart.elapsedNow().inWholeNanoseconds, lastFrameNanos + 1)
        clock.sendFrame(frameNanos)
        lastFrameNanos = frameNanos
        applyChanges()
        completedFrames.store(epoch)
      }
    }
  }

  /**
   * Replaces the host's content with [content] composed into [rootNode]. The previous content
   * composition, if any, is disposed first. The composition happens on the host dispatcher, so this
   * returns before the content has applied.
   */
  fun setContent(content: @Composable () -> Unit) {
    if (closed) {
      logger?.w { "setContent on a closed StyleCompositionHost; content dropped" }
      return
    }
    scope.launch {
      disposeComposition()
      disposed = false
      val composition = Composition(MapNodeApplier(rootNode), recomposer)
      this@StyleCompositionHost.composition = composition
      try {
        composition.setContent {
          WithInheritedLocals {
            val locals =
              listOfNotNull(
                LocalDensity provides density,
                LocalLayoutDirection provides layoutDirection,
                LocalStyleNode provides rootNode,
                mapState?.let { LocalMapState provides it },
              )
            CompositionLocalProvider(*locals.toTypedArray()) { content() }
          }
        }
        applyStyleRevision()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        rethrowIfFatal(error)
        reportError("Style content failed to compose", error)
      }
    }
  }

  @Composable
  private fun WithInheritedLocals(content: @Composable () -> Unit) {
    val locals = inheritedLocals
    if (locals == null) content() else CompositionLocalProvider(locals, content = content)
  }

  /** Idempotent; the teardown itself runs on the host dispatcher after any in-flight frame. */
  override fun close() {
    if (closed) return
    closed = true
    writeObserver.dispose()
    scope.launch {
      try {
        disposeComposition()
      } finally {
        recomposer.cancel()
        job.cancel()
      }
    }
  }

  /** Suspends until the recomposer has shut down, marking the close teardown finished. */
  internal suspend fun awaitShutdown() {
    recomposer.currentState.first { it == Recomposer.State.ShutDown }
  }

  /** Posts [block] onto the host dispatcher. Native callbacks use this to enter [MapState]. */
  internal fun postLogical(block: () -> Unit) {
    if (closed) return
    scope.launch { block() }
  }

  private fun applyStyleRevision() {
    val map = mapState
    if (map != null) map.syncStyleComposition() else applyStyleRevision(rootNode)
  }

  private fun applyChanges() {
    if (closed || disposed) return
    try {
      applyStyleRevision()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      reportError("Applying style changes failed", error)
    }
  }

  /** Runs the desired-state sync outside a pumped frame, for a style swap. */
  fun requestApplyChanges() {
    if (closed) return
    scope.launch { applyChanges() }
  }

  /**
   * Suspends until the host is quiescent: every task queued on the host dispatcher has run and the
   * recomposer reports no pending work. A recomposition can queue further host tasks — a snapshot
   * notification, a pumped frame, the sync after it — so one queued marker is not enough; the loop
   * re-joins until a marker runs with the recomposer still idle across two consecutive checks.
   * Waiting out pending work uses the recomposer's state flow, which queues no dispatcher task, so
   * a virtual-time test clock is free to advance the frame pacing delay meanwhile.
   */
  internal suspend fun awaitPendingWork() {
    if (closed) return
    // Quiescence must hold for two consecutive full rounds: an invalidation can be one
    // asynchronous scheduling hop away from every check in a single round.
    var cleanRounds = 0
    while (true) {
      if (closed) throw closedWhileSettling()
      try {
        // Delivers any written-but-unnotified snapshot state, so a caller's plain write is enough.
        withContext(dispatcher) { Snapshot.sendApplyNotifications() }
        recomposer.currentState.first { it != Recomposer.State.PendingWork }
        scope.launch {}.join()
        if (recomposer.currentState.value == Recomposer.State.PendingWork) {
          cleanRounds = 0
          continue
        }
        // A requested frame runs applyChanges after the pacing delay; that sync is pending work.
        if (frameIsPending) {
          cleanRounds = 0
          continue
        }
        // A notification queued during the last task must be delivered before deciding.
        scope.launch {}.join()
        if (recomposer.currentState.value != Recomposer.State.PendingWork && !frameIsPending) {
          if (++cleanRounds >= 2) return
        } else {
          cleanRounds = 0
        }
      } catch (error: CancellationException) {
        if (closed) throw closedWhileSettling()
        throw error
      }
    }
  }

  /**
   * Runs [block] on the UI dispatcher the host uses. A caller already on that dispatcher continues
   * inline, so `runBlocking` on Main does not deadlock. Off-host suspend APIs hop here so their
   * commits serialize with [postLogical] and with style sync.
   */
  internal suspend fun <T> runOnHost(block: () -> T): T = withContext(uiDispatcher) { block() }

  /**
   * Runs [block] on the host, blocking the caller when a hop is required. A caller already on the
   * host, or a test that drives a virtual dispatcher, continues inline.
   */
  internal fun <T> runOnHostBlocking(block: () -> T): T {
    if (isVirtualTestDispatcher(uiDispatcher)) return block()
    val onHost = runCatching {
      !uiDispatcher.isDispatchNeeded(EmptyCoroutineContext)
    }
      .getOrDefault(false)
    if (onHost) return block()
    return runBlockingOn(uiDispatcher, block)
  }

  /**
   * Runs [block] on the host dispatcher and returns its result, rethrowing what it throws. The
   * mutex serializes callers even when the dispatcher has many threads. Throws
   * [IllegalStateException] when the host has closed.
   */
  internal suspend fun <T> runSerialized(block: () -> T): T {
    check(!closed) { "MapState is closed; no loaded style to mutate" }
    val caller = currentCoroutineContext()
    // runCatching keeps an ordinary failure out of the host's job, which one failed op must not
    // cancel; fatal errors still propagate. A caller cancelled while its block queued must not
    // mutate the style it no longer awaits.
    val deferred = scope.async {
      serialized.withLock {
        caller.ensureActive()
        runCatching(block).onFailure { rethrowIfFatal(it) }
      }
    }
    val result =
      try {
        deferred.await()
      } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("MapState is closed; no loaded style to mutate", error)
      }
    return result.getOrThrow()
  }

  private fun disposeComposition() {
    if (disposed) return
    try {
      composition?.dispose()
    } finally {
      composition = null
      disposed = true
    }
    // Disposal only empties the desired state; this sync removes the content from the engine.
    try {
      applyStyleRevision()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      reportError("Applying style changes after disposal failed", error)
    }
  }
}

internal val LocalStyleNode = staticCompositionLocalOf<StyleNode> { throw IllegalStateException() }

/** The pacing floor between pumped frames, matching a 60 Hz display. */
private const val FRAME_INTERVAL_NANOS = 16_000_000L

/**
 * Answers every dispatch check with true, so a resume always enqueues instead of running inline.
 */
private class QueueingDispatcher(private val delegate: CoroutineDispatcher) :
  CoroutineDispatcher() {
  override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

  override fun dispatch(context: CoroutineContext, block: Runnable) =
    delegate.dispatch(context, block)
}
