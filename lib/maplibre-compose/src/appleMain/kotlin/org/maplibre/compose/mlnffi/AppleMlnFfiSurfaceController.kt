package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.toLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.compose.util.throwCleanupFailures
import platform.Foundation.NSThread
import platform.QuartzCore.CAMetalLayer

/**
 * Schedules surface demands on main; the native Metal worker renders and presents into the layer.
 * The layer's drawable queue paces presentation, with [maximumFps] delaying the next demand.
 */
internal class AppleMlnFfiSurfaceController(
  renderer: MlnFfiMapRenderer,
  logger: MapLog?,
  maximumFps: Int? = null,
  private val onFailure: (Throwable) -> Unit,
) : MlnFfiSurfaceRenderLoop<CAMetalLayer>(renderer, logger, "Apple", maximumFps), AutoCloseable {
  override val isClosed: Boolean
    get() = closeRequested

  override val backends = RenderBackendPair(MapRenderBackend.Metal, ComposeRenderBackend.Metal)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val completion = CompletableDeferred<Result<Unit>>()
  private val cleanupFailures = mutableListOf<Throwable>()
  @Volatile private var closeRequested = false

  /** Retains the layer until native teardown has released it. */
  fun surfaceLayoutChanged(layer: CAMetalLayer, extent: MapExtent) {
    enqueueRenderer {
      if (closeRequested) return@enqueueRenderer
      if (layer !== surface) attachSurface(extent) { layer }
      else resizeSurface(extent, reallocates = false)
    }
  }

  /** A stale loss never detaches its replacement. */
  fun surfaceDestroyed(layer: CAMetalLayer): Deferred<Result<Unit>> {
    val detached = CompletableDeferred<Result<Unit>>()
    val accepted = enqueueRenderer {
      val result = runCatching {
        if (surface === layer) detachSurface()
      }
        .onFailure(::rethrowIfFatal)
      detached.complete(result)
      result.exceptionOrNull()?.let {
        cleanupFailures.add(it)
        close()
        onFailure(it)
      }
    }
    return if (accepted) detached else completion
  }

  override fun close() {
    if (closeRequested) return
    closeRequested = true
    scope.launch {
      runCatching { detachSurface() }.exceptionOrNull()?.let(cleanupFailures::add)
      closed = true
      completion.complete(runCatching { cleanupFailures.throwCleanupFailures() })
      scope.cancel()
    }
  }

  suspend fun awaitClosed() = completion.await().getOrThrow()

  override fun enqueueRenderer(action: () -> Unit): Boolean =
    schedule(Duration.ZERO, action) != null

  override fun isRenderThread(): Boolean = NSThread.isMainThread

  override fun schedule(delay: Duration, action: () -> Unit): ScheduledAction? {
    if (closeRequested) return null
    val job = scope.launch {
      delay(delay)
      try {
        action()
      } catch (error: Throwable) {
        rethrowIfFatal(error)
        cleanupFailures.add(error)
        close()
        onFailure(error)
      }
    }
    return ScheduledAction { job.cancel() }
  }

  override fun <T> runAndWait(action: () -> T): T {
    val done = MlnFfiSignal()
    var result: Result<T>? = null
    check(
      enqueueRenderer {
        result = runCatching(action)
        done.signal()
      },
    ) {
      "The Apple map surface controller is shutting down"
    }
    done.await()
    return checkNotNull(result).getOrThrow()
  }

  @OptIn(BetaInteropApi::class)
  override fun renderTarget(surface: CAMetalLayer, extent: MapExtent, generation: Long) =
    MetalSurfaceTarget(
      device = DefaultMetalDevice,
      layer = NativeHandle(surface.objcPtr().toLong()),
      extent = extent,
      generation = generation,
    )

  override fun onTerminalFailure(message: String, error: Throwable, releaseFailure: Throwable?) {
    releaseFailure?.let(cleanupFailures::add)
    onFailure(error)
  }

  private companion object {
    /** The FFI selects the system default Metal device for a null handle. */
    val DefaultMetalDevice = NativeHandle(0L)
  }
}
