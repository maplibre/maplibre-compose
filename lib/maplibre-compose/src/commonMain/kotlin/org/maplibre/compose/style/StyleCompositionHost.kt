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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.rethrowIfFatal

/**
 * Owns one style [Composition], its [Recomposer], and the frame clock that drives it, with no
 * hosting UI composition.
 *
 * The host pumps its own frames: [BroadcastFrameClock.onNewAwaiters] fires when the recomposer
 * needs a frame, and the frame-pump coroutine answers it on [dispatcher]. A global snapshot write
 * observer stands in for the platform's GlobalSnapshotManager so plain snapshot writes reach the
 * recomposer even when no UI composition is running.
 *
 * [setContent] marshals onto [dispatcher], so a caller on the UI thread never runs a style
 * mutation. The host calls [StyleNode.applyChanges] after the initial composition and after each
 * frame; sources still attach before layers because effects run inside the frame and this flush
 * runs after it.
 */
internal class StyleCompositionHost(
  private val rootNode: StyleNode,
  private val dispatcher: CoroutineDispatcher,
  density: Density,
  layoutDirection: LayoutDirection,
  // Mutable so a host constructed before the composable's logger is known picks it up.
  var logger: Logger?,
  // Null only in tests that compose a style with no owning map.
  private val mapState: MapState? = null,
  private val onClosed: () -> Unit = {},
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

  private val clock = BroadcastFrameClock { frameSignal.trySend(Unit) }
  private val job = Job()
  private val exceptionHandler = CoroutineExceptionHandler { _, error ->
    logger?.e(error) { "Uncaught error on the style composition host" }
  }
  private val scope = CoroutineScope(job + dispatcher + clock + exceptionHandler)

  internal val recomposer = Recomposer(scope.coroutineContext)

  // Host-thread confined; every access rides a coroutine on the single-threaded dispatcher.
  private var composition: Composition? = null

  /** True before the first [setContent] and after each disposal; there is nothing to sync then. */
  private var disposed = true

  private var closed = false

  /** How many frames this host has pumped; a diagnostic counter for tests. */
  internal var framesPumped: Int = 0
    private set

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

  private val writeObserver = Snapshot.registerGlobalWriteObserver { snapshotSignal.trySend(Unit) }

  init {
    rootNode.requestSync = ::requestApplyChanges
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
      var time = 0L
      for (unused in frameSignal) {
        framesPumped++
        time += 16_000_000L
        clock.sendFrame(time)
        applyChanges()
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
        rootNode.applyChanges()
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
        onClosed()
      }
    }
  }

  private fun applyChanges() {
    if (disposed) return
    try {
      rootNode.applyChanges()
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
      rootNode.applyChanges()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      reportError("Applying style changes after disposal failed", error)
    }
  }
}

internal val LocalStyleNode = staticCompositionLocalOf<StyleNode> { throw IllegalStateException() }
