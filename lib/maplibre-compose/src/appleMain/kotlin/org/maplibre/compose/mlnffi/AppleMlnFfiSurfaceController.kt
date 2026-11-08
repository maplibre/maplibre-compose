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
import org.maplibre.nativeffi.FramePacerHandle
import org.maplibre.nativeffi.FramePacerOptions
import org.maplibre.nativeffi.appleFramePacerCreate
import platform.Foundation.NSThread
import platform.QuartzCore.CAMetalLayer

/**
 * Hosts a map in a `CAMetalLayer` from the main thread; the native Metal worker renders and
 * presents into the layer.
 *
 * A frame pacer from [appleFramePacerCreate] demands the render session's frames at the display's
 * refreshes, within [maximumFps]. On macOS that pacer follows the main display, the one with the
 * menu bar, even while the layer's window is on another display. A frame wake queues a pass on main
 * that only takes the session's frame results.
 *
 * The render session outlives its layer: when the layer goes away or the host becomes inactive, the
 * session only releases its target, and the next layer becomes its new target, so the map keeps its
 * renderer and tiles.
 */
internal class AppleMlnFfiSurfaceController(
  renderer: MlnFfiMapRenderer,
  logger: MapLog?,
  maximumFps: Int? = null,
  private val onFailure: (Throwable) -> Unit,
) :
  MlnFfiSurfaceRenderLoop<CAMetalLayer>(
    renderer,
    logger,
    "Apple",
    // The frame pacer keeps the frame-rate limit.
    maximumFps = null,
    retainsSessionAcrossSurfaces = true,
  ),
  AutoCloseable {
  override val isClosed: Boolean
    get() = closeRequested

  override val backends = RenderBackendPair(MapRenderBackend.Metal, ComposeRenderBackend.Metal)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val completion = CompletableDeferred<Result<Unit>>()
  private val cleanupFailures = mutableListOf<Throwable>()
  @Volatile private var closeRequested = false
  private var pacerFps = maximumFps

  /** Main thread. Created with the first layer, and replaced when [maximumFps] changes. */
  internal var framePacer: FramePacerHandle? = null
    private set(value) {
      field = value
      frameDemands = value?.let(MlnFfiFrameDemands::Paced) ?: MlnFfiFrameDemands.Parked
    }

  override var frameDemands: MlnFfiFrameDemands = MlnFfiFrameDemands.Parked
    private set

  /** Retains the layer until native teardown has released it. */
  fun surfaceLayoutChanged(layer: CAMetalLayer, extent: MapExtent) {
    enqueueRenderer {
      if (closeRequested) return@enqueueRenderer
      if (layer !== surface) {
        // The render session uses the old layer until it lets go of it.
        loseSurface()
        attachSurface(extent) {
          if (framePacer == null) framePacer = createFramePacer(pacerFps)
          layer
        }
      } else resizeSurface(extent, reallocates = false)
    }
  }

  /**
   * Releases the layer from the render session and keeps the session for the next layer. A stale
   * loss never detaches its replacement.
   */
  fun surfaceDestroyed(layer: CAMetalLayer): Deferred<Result<Unit>> {
    val detached = CompletableDeferred<Result<Unit>>()
    val accepted = enqueueRenderer {
      val result = runCatching {
        if (surface === layer) loseSurface()
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
      // Sessions that this pacer still served stay paced until they are released.
      framePacer?.release()
      framePacer = null
      closed = true
      completion.complete(runCatching { cleanupFailures.throwCleanupFailures() })
      scope.cancel()
    }
  }

  suspend fun awaitClosed() = completion.await().getOrThrow()

  override fun setMaximumFps(maximumFps: Int?) {
    schedule(Duration.ZERO) {
      if (closed || pacerFps == maximumFps) return@schedule
      pacerFps = maximumFps
      val previous = framePacer ?: return@schedule
      // A pacer's rate is fixed. The next frame pass hands the new pacer to the session, which then
      // leaves the old one.
      // TODO: change the pacer's rate in place once
      // https://github.com/maplibre/maplibre-native-ffi/issues/1758 allows it.
      framePacer = createFramePacer(maximumFps)
      previous.release()
      requestFrame()
    }
  }

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

    fun createFramePacer(maximumFps: Int?): FramePacerHandle =
      appleFramePacerCreate(
        maximumFps
          ?.takeIf { it > 0 }
          ?.let { FramePacerOptions(maximumFramesPerSecond = it.toUInt()) },
      )
  }
}
