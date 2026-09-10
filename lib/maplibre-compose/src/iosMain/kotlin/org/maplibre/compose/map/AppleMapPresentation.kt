package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.toLong
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.internal.FeatureClickDispatcher
import org.maplibre.compose.mlnffi.IosMlnFfiSurfaceController
import org.maplibre.compose.mlnffi.MapRenderBackend
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import platform.QuartzCore.CAMetalLayer
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Presents [state] on a caller-owned [CAMetalLayer], without a Compose UI hierarchy.
 *
 * Use [MaplibreMapView] to host this presentation in UIKit; it supplies the layer, size, and
 * display scale. Custom layer hosts use [attachLayer]. Rendering runs while [isActive] is true. The
 * host must update activation from its own scene lifecycle and [close] when it no longer needs the
 * map.
 *
 * One presentation or [MaplibreMap] presents a state at a time. Closing the presentation disposes
 * style effects but keeps the state and its camera. Closing the state closes the presentation.
 * Style content and camera operations work without an attached layer. UI composables and
 * view-dependent composition locals are not available in standalone style content.
 *
 * Forward recognized gestures through [MapState], for example [MapState.panBy]. All methods and
 * property updates must run on the main thread. Currently available on iOS.
 */
public class AppleMapPresentation
internal constructor(
  public val state: MapState,
  private val owner: MapPresentationOwnerToken,
  initialOptions: MapViewOptions,
) : AutoCloseable {
  /** Creates a standalone presentation. [density] also supplies the initial detached map scale. */
  public constructor(
    state: MapState,
    cameraPadding: PaddingValues = PaddingValues(0.dp),
    cameraConstraints: CameraConstraints = CameraConstraints(),
    renderOptions: RenderOptions = RenderOptions.Standard,
    interactions: MapInteractions = MapInteractions.Standard,
    density: Density = Density(1f),
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    isActive: Boolean = true,
  ) : this(
    state,
    MapPresentationOwnerToken(),
    MapViewOptions(
      cameraPadding = cameraPadding,
      cameraConstraints = cameraConstraints,
      renderOptions = renderOptions,
      interactions = interactions,
    ),
  ) {
    this.density = density
    this.layoutDirection = layoutDirection
    this.isActive = isActive
    startComposition()
  }

  private val logger = state.runtime.logger
  private var options by mutableStateOf(initialOptions)
  private var binding: LayerBinding? by mutableStateOf(null)
  private var controller: IosMlnFfiSurfaceController? = null
  private var composition: PresentationComposition? = null
  private var scope: CoroutineScope? = null
  internal var isClosed: Boolean by mutableStateOf(false)
    private set

  init {
    checkAppleMainThread()
    check(!state.isClosed) { "The map state is closed" }
  }

  /** The error that ended this presentation, if any. Create a new presentation to retry. */
  public var failure: Throwable? by mutableStateOf(null)
    private set

  private var active by mutableStateOf(true)

  /** Whether rendering is enabled. Style composition continues while inactive. */
  public var isActive: Boolean
    get() = active
    set(value) {
      checkOpen()
      active = value
      controller?.setActive(value)
    }

  private var contentDensity by mutableStateOf(Density(1f))

  /**
   * Density and font scale for standalone style content. An attached layer supplies its own
   * density. Compose hosts inherit both from their composition instead.
   */
  public var density: Density
    get() = contentDensity
    set(value) {
      checkOpen()
      validateDensity(value)
      contentDensity = value
    }

  private var contentLayoutDirection by mutableStateOf(LayoutDirection.Ltr)

  /** Layout direction for standalone style content. Compose hosts inherit their caller's value. */
  public var layoutDirection: LayoutDirection
    get() = contentLayoutDirection
    set(value) {
      checkOpen()
      contentLayoutDirection = value
    }

  public var cameraPadding: PaddingValues
    get() = options.cameraPadding
    set(value) = update { copy(cameraPadding = value) }

  public var cameraConstraints: CameraConstraints
    get() = options.cameraConstraints
    set(value) = update { copy(cameraConstraints = value) }

  public var renderOptions: RenderOptions
    get() = options.renderOptions
    set(value) = update { copy(renderOptions = value) }

  public var interactions: MapInteractions
    get() = options.interactions
    set(value) = update { copy(interactions = value) }

  /**
   * Attaches [layer], replacing the previous binding. [width] and [height] are physical pixels;
   * [density] is physical pixels per logical pixel. A density change recreates the native map.
   *
   * The host owns the layer and its layout. Use a BGRA8Unorm layer dedicated to this presentation;
   * MapLibre owns its drawable size and presentation. Close the binding before releasing or
   * repurposing the layer. Detachment waits for rendering to stop using it.
   */
  public fun attachLayer(
    layer: CAMetalLayer,
    width: Int,
    height: Int,
    density: Float,
  ): LayerBinding {
    checkOpen()
    val extent = layerExtent(width, height, density)
    binding?.close()
    return LayerBinding(this, layer, extent).also { binding = it }
  }

  /** Stops using the layer and disposes owned style content. Leaves [state] open. */
  override fun close() {
    checkAppleMainThread()
    if (isClosed) return
    isClosed = true
    binding = null
    try {
      try {
        controller?.close()
      } finally {
        controller = null
        composition?.close()
      }
    } catch (error: Throwable) {
      logger?.e(error) { "Apple map presentation cleanup failed" }
      if (failure == null) failure = error
    } finally {
      composition = null
      scope?.cancel()
      scope = null
    }
  }

  private fun startComposition() {
    val scope =
      CoroutineScope(
        SupervisorJob() +
          Dispatchers.Main.immediate +
          CoroutineExceptionHandler { _, error -> postAppleMain { fail(error) } }
      )
    this.scope = scope
    // Only the standalone recomposer uses this clock. Metal drawables pace the renderer itself.
    lateinit var clock: BroadcastFrameClock
    clock = BroadcastFrameClock {
      scope.launch {
        delay(16)
        clock.sendFrame((NSProcessInfo.processInfo.systemUptime * 1_000_000_000).toLong())
      }
    }
    try {
      composition =
        PresentationComposition(CoroutineScope(scope.coroutineContext + clock), ::postAppleMain) {
          val scale = binding?.extent?.scaleFactor?.toFloat() ?: density.density
          CompositionLocalProvider(
            LocalDensity provides Density(scale, density.fontScale),
            LocalLayoutDirection provides layoutDirection,
          ) {
            Content()
          }
        }
    } catch (error: Throwable) {
      close()
      throw error
    }
    scope.launch {
      snapshotFlow { state.isClosed }.first { it }
      postAppleMain { close() }
    }
  }

  /** The Compose host calls this inline, preserving its locals, effects, and frame clock. */
  @Composable
  internal fun Content(
    options: MapViewOptions = this.options,
    content: @Composable (MlnFfiMapSession, FeatureClickDispatcher) -> Unit = { _, _ -> },
  ) {
    if (isClosed || state.isClosed) return
    val available = remember { MapRenderBackend.METAL in loadRuntimeBackends(logger) }
    DisposableEffect(available) {
      if (!available)
        postAppleMain {
          fail(IllegalStateException("No Apple Metal map render backend is available"))
        }
      onDispose {}
    }
    MlnFfiMapPresentation(MapRenderBackend.METAL, state, owner, options) { session, clicks ->
      val controller =
        remember(session) {
          IosMlnFfiSurfaceController(session, logger, options.renderOptions.maximumFps) { error ->
            postAppleMain { fail(error) }
          }
        }
      DisposableEffect(controller) {
        this@AppleMapPresentation.controller = controller
        onDispose {
          if (this@AppleMapPresentation.controller === controller) {
            this@AppleMapPresentation.controller = null
          }
          controller.close()
        }
      }
      SideEffect {
        controller.setMaximumFps(options.renderOptions.maximumFps)
        controller.setActive(isActive)
      }
      val binding = binding?.takeIf { available && session.canPresentFrames }
      if (binding != null) {
        val extent = binding.extent
        DisposableEffect(controller, binding) {
          controller.surfaceLayoutChanged(binding.layer.objcPtr().toLong(), extent)
          onDispose { controller.surfaceDestroyed() }
        }
        SideEffect { controller.surfaceLayoutChanged(binding.layer.objcPtr().toLong(), extent) }
      }
      content(session, clicks)
    }
  }

  private fun update(change: MapViewOptions.() -> MapViewOptions) {
    checkOpen()
    options = options.change()
  }

  private fun fail(error: Throwable) {
    if (isClosed || failure != null) return
    failure = error
    logger?.e(error) { "Apple map presentation failed" }
    close()
  }

  internal fun checkOpen() {
    checkAppleMainThread()
    check(!isClosed && !state.isClosed) { "The map presentation is closed" }
  }

  private fun detach(binding: LayerBinding) {
    checkAppleMainThread()
    if (this.binding !== binding) return
    this.binding = null
    controller?.surfaceDestroyed()
  }

  /**
   * The presentation's use of one layer. Closed or replaced bindings ignore updates. The binding
   * retains the layer until detachment has stopped rendering; the host still owns its contents.
   */
  public class LayerBinding
  internal constructor(
    private val presentation: AppleMapPresentation,
    internal val layer: CAMetalLayer,
    extent: MapExtent,
  ) : AutoCloseable {
    internal var extent by mutableStateOf(extent)

    /** Updates the physical size and density together. */
    public fun update(width: Int, height: Int, density: Float) {
      checkAppleMainThread()
      if (presentation.isClosed || presentation.binding !== this) return
      extent = layerExtent(width, height, density)
    }

    /** Waits for rendering to stop using the layer. Does not release the host's layer. */
    override fun close(): Unit = presentation.detach(this)
  }
}

private fun layerExtent(width: Int, height: Int, density: Float): MapExtent {
  require(width > 0 && height > 0) { "Layer dimensions must be positive" }
  require(density.isFinite() && density > 0f) { "Density must be positive and finite" }
  return MapExtent.fromPhysical(width, height, density.toDouble())
}

private fun validateDensity(density: Density) {
  require(density.density.isFinite() && density.density > 0f) {
    "Density must be positive and finite"
  }
  require(density.fontScale.isFinite() && density.fontScale > 0f) {
    "Font scale must be positive and finite"
  }
}

internal fun checkAppleMainThread() {
  check(NSThread.isMainThread) { "The map presentation must be used on the main thread" }
}

internal fun postAppleMain(action: () -> Unit) {
  dispatch_async(dispatch_get_main_queue(), action)
}
