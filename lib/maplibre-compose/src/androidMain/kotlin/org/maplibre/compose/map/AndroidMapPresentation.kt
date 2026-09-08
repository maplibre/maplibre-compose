package org.maplibre.compose.map

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import android.view.View
import androidx.annotation.MainThread
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.AndroidUiFrameClock
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.mlnffi.AndroidMlnFfiSurfaceController
import org.maplibre.compose.mlnffi.MapRenderBackend

/**
 * Presents [state] on an Android [Surface] that your code owns, without a Compose UI view.
 *
 * One presentation or [MaplibreMap] presents a state at a time; a second throws. Closing the
 * presentation keeps the state and its camera. Closing the state, or destroying [lifecycle], closes
 * the presentation. Rendering pauses while [lifecycle] is below [Lifecycle.State.STARTED].
 *
 * Style content composes with the [Context] and [Configuration] given here and the density of the
 * attached Surface. UI composables and view-dependent locals are not available in style content.
 * Style content and camera operations work while no Surface is attached. No frames are produced
 * until the style can be presented, so show your own loading content on the Surface until then.
 *
 * Pass gestures with the [MapState] methods, such as [MapState.panBy].
 *
 * Call every method on the Android main thread.
 */
@MainThread
public class AndroidMapPresentation(
  private val context: Context,
  public val state: MapState,
  private val lifecycle: Lifecycle,
  configuration: Configuration = context.resources.configuration,
  cameraPadding: PaddingValues = PaddingValues(0.dp),
  cameraConstraints: CameraConstraints = CameraConstraints(),
  renderOptions: RenderOptions = RenderOptions.Standard,
  interactions: MapInteractions = MapInteractions.Standard,
) : AutoCloseable {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val logger = state.runtime.logger
  // The immediate dispatcher lets close() dispose style effects before it returns.
  private val scope =
    CoroutineScope(
      SupervisorJob() +
        Dispatchers.Main.immediate +
        AndroidUiFrameClock(Choreographer.getInstance()) +
        CoroutineExceptionHandler { _, error -> mainHandler.post { fail(error) } }
    )
  private var options by
    mutableStateOf(
      MapViewOptions(
        cameraPadding = cameraPadding,
        cameraConstraints = cameraConstraints,
        renderOptions = renderOptions,
        interactions = interactions,
      )
    )
  private var configuration by mutableStateOf(Configuration(configuration))
  private var binding: SurfaceBinding? by mutableStateOf(null)
  private var active by mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
  private var controller: AndroidMlnFfiSurfaceController? = null
  private var composition: AndroidPresentationComposition? = null
  private var closed = false
  private val lifecycleObserver = LifecycleEventObserver { _, event ->
    if (event == Lifecycle.Event.ON_DESTROY) close()
    else active = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
  }

  /** The error that ended this presentation, if any. Create a new presentation to retry. */
  public var failure: Throwable? by mutableStateOf(null)
    private set

  public var cameraPadding: PaddingValues
    get() = options.cameraPadding
    set(value) = update { copy(cameraPadding = value) }

  public var cameraConstraints: CameraConstraints
    get() = options.cameraConstraints
    set(value) = update { copy(cameraConstraints = value) }

  public var renderOptions: RenderOptions
    get() = options.renderOptions
    set(value) = update { copy(renderOptions = value) }

  /** Camera permissions and click callbacks for recognized gestures and interactive layers. */
  public var interactions: MapInteractions
    get() = options.interactions
    set(value) = update { copy(interactions = value) }

  init {
    checkMainThread()
    check(lifecycle.currentState != Lifecycle.State.DESTROYED) { "The lifecycle is destroyed" }
    check(!state.isClosed) { "The map state is closed" }
    val runtimeBackends = loadRuntimeBackends(logger)
    val backend = runtimeBackends.firstOrNull() ?: MapRenderBackend.OPENGL
    val owner = MapPresentationOwnerToken()
    try {
      composition = AndroidPresentationComposition(scope) { Content(backend, owner) }
    } catch (error: Throwable) {
      scope.cancel()
      throw error
    }
    lifecycle.addObserver(lifecycleObserver)
    if (backend !in runtimeBackends) {
      fail(IllegalStateException("No Android map render backend is available"))
    }
    scope.launch {
      snapshotFlow { state.isClosed }.first { it }
      // Apply notifications can resume this collector inside the map's lifecycle lock.
      mainHandler.post { close() }
    }
  }

  /**
   * Applies the locale, layout direction, font scale, and night mode in [configuration] to style
   * content.
   */
  public fun updateConfiguration(configuration: Configuration) {
    checkOpen()
    this.configuration = Configuration(configuration)
  }

  /**
   * Presents on [surface] until the returned binding closes, replacing the current binding. [width]
   * and [height] are physical pixels. [density] is physical pixels per logical pixel. A density
   * change recreates the native map.
   *
   * Close the binding before releasing the Surface. Closing waits for rendering to stop using it.
   */
  public fun attachSurface(
    surface: Surface,
    width: Int,
    height: Int,
    density: Float,
  ): SurfaceBinding {
    checkOpen()
    require(surface.isValid) { "The surface is not valid" }
    validateSurface(width, height, density)
    binding?.close()
    return SurfaceBinding(this, surface, width, height, density).also { binding = it }
  }

  internal fun updateSurface(
    binding: SurfaceBinding,
    width: Int,
    height: Int,
    density: Float,
  ) {
    checkMainThread()
    if (closed || this.binding !== binding) return
    validateSurface(width, height, density)
    binding.width = width
    binding.height = height
    binding.density = density
  }

  /** Stops using the attached Surface, then disposes style content. Leaves [state] open. */
  override fun close() {
    checkMainThread()
    if (closed) return
    closed = true
    lifecycle.removeObserver(lifecycleObserver)
    binding = null
    val composition = composition
    this.composition = null
    try {
      composition?.close()
    } catch (error: Throwable) {
      logger?.e(error) { "Android map presentation cleanup failed" }
      if (failure == null) failure = error
    } finally {
      scope.cancel()
    }
  }

  internal fun detach(binding: SurfaceBinding) {
    checkMainThread()
    if (this.binding !== binding) return
    this.binding = null
    // Synchronous, so the caller can release the Surface when this returns. The composition's
    // effect disposal later finds nothing attached.
    controller?.surfaceDestroyed()
  }

  internal fun checkOpen() {
    checkMainThread()
    check(!closed && !state.isClosed) { "The map presentation is closed" }
  }

  private fun update(change: MapViewOptions.() -> MapViewOptions) {
    checkOpen()
    options = options.change()
  }

  private fun fail(error: Throwable) {
    if (closed || failure != null) return
    failure = error
    logger?.e(error) { "Android map presentation failed" }
    close()
  }

  @Composable
  private fun Content(backend: MapRenderBackend, owner: MapPresentationOwnerToken) {
    val configuration = configuration
    val density = binding?.density ?: (configuration.densityDpi / 160f)
    val configuredContext =
      remember(configuration) { context.createConfigurationContext(configuration) }
    CompositionLocalProvider(
      LocalContext provides configuredContext,
      LocalConfiguration provides configuration,
      LocalDensity provides Density(density, configuration.fontScale),
      LocalLayoutDirection provides
        if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl
        else LayoutDirection.Ltr,
    ) {
      MlnFfiMapPresentation(backend, state, owner, options) { session, _ ->
        SurfaceHost(session, backend)
      }
    }
  }

  /** Attaches the current binding to [session] while a style can be presented. */
  @Composable
  private fun SurfaceHost(session: MlnFfiMapSession, backend: MapRenderBackend) {
    val maximumFps = options.renderOptions.maximumFps
    val controller =
      remember(session) {
        AndroidMlnFfiSurfaceController(session, backend, logger, maximumFps) { error ->
          mainHandler.post { fail(error) }
        }
      }
    DisposableEffect(controller) {
      this@AndroidMapPresentation.controller = controller
      onDispose {
        if (this@AndroidMapPresentation.controller === controller) {
          this@AndroidMapPresentation.controller = null
        }
        controller.close()
      }
    }
    SideEffect { controller.setMaximumFps(maximumFps) }
    val active = active
    DisposableEffect(controller, active) {
      controller.setActive(active)
      onDispose {}
    }
    // An attached Surface would show black until a style loads; the host keeps its own image.
    val binding = binding?.takeIf { session.canPresentFrames } ?: return
    val scaleFactor = LocalDensity.current.density.toDouble()
    // Read the size here, not in the effect, so a resize recomposes this host.
    val width = binding.width
    val height = binding.height
    DisposableEffect(controller, binding) {
      controller.surfaceCreated(binding.surface, width, height, scaleFactor)
      onDispose { controller.surfaceDestroyed() }
    }
    SideEffect { controller.surfaceChanged(width, height, scaleFactor) }
  }

  /**
   * The map's use of one Surface. Update the physical size and density together when they change.
   * Close the binding before releasing the Surface. A closed or replaced binding ignores updates.
   */
  @MainThread
  public class SurfaceBinding
  internal constructor(
    private val presentation: AndroidMapPresentation,
    internal val surface: Surface,
    width: Int,
    height: Int,
    density: Float,
  ) : AutoCloseable {
    internal var width by mutableStateOf(width)
    internal var height by mutableStateOf(height)
    internal var density by mutableStateOf(density)

    /** Updates the physical size and density. Ignored once this binding is closed or replaced. */
    public fun update(width: Int, height: Int, density: Float): Unit =
      presentation.updateSurface(this, width, height, density)

    /** Waits for rendering to stop using the Surface. */
    override fun close(): Unit = presentation.detach(this)
  }
}

private fun validateSurface(width: Int, height: Int, density: Float) {
  require(width > 0 && height > 0) { "Surface dimensions must be positive" }
  require(density.isFinite() && density > 0f) { "Density must be positive and finite" }
}

private fun checkMainThread() {
  check(Looper.myLooper() == Looper.getMainLooper()) {
    "The map presentation must be used on the Android main thread"
  }
}
