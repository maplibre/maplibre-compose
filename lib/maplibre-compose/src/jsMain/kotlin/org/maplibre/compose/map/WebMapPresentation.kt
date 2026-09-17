package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.compose.interaction.MapInteractions
import web.dom.document
import web.html.HTMLElement

/**
 * Presents [state] in an HTML container without a Compose UI hierarchy or Skiko initialization.
 * Create and use the presentation on the browser's main thread after the document body exists.
 *
 * One presentation or [MaplibreMap] presents a state at a time. Closing the presentation disposes
 * style effects and its DOM but leaves the state and camera open. Closing the state closes the
 * presentation. Style content and camera operations continue between container attachments, using
 * the last viewport size, or one CSS pixel before the first attachment.
 *
 * The host supplies layout and calls [close] when finished. This presentation installs no gesture
 * handlers or controls. Forward recognized gestures through [MapState], such as [MapState.panBy],
 * and supply attribution and loading UI in your framework. Standalone style content has density and
 * layout direction, but no UI composables or view-dependent composition locals.
 */
public class WebMapPresentation(
  public val state: MapState,
  viewportInsets: PaddingValues = PaddingValues(0.dp),
  cameraConstraints: CameraConstraints = CameraConstraints(),
  renderOptions: RenderOptions = RenderOptions.Standard,
  interactions: MapInteractions = MapInteractions.Standard,
  layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  isActive: Boolean = true,
) : AutoCloseable {
  private val logger = state.runtime.logger
  private var options by
    mutableStateOf(
      MapViewOptions(
        viewportInsets = viewportInsets,
        cameraConstraints = cameraConstraints,
        renderOptions = renderOptions,
        interactions = interactions,
      )
    )
  private var direction by mutableStateOf(layoutDirection)
  private var density by mutableStateOf(window.devicePixelRatio.toFloat())
  private var composition: PresentationComposition? = null
  private var binding: ContainerBinding? = null
  private var surface: WebMapSurface? = null
  private var clockFrame: Int? = null
  private var closed = false
  private val scope =
    CoroutineScope(
      SupervisorJob() +
        Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, error -> post { fail(error) } }
    )

  /** The error that ended this presentation, if any. Create a new presentation to retry. */
  public var failure: Throwable? by mutableStateOf(null)
    private set

  private var active by mutableStateOf(isActive)

  /** Whether the map renders. Style composition continues while inactive. */
  public var isActive: Boolean
    get() = active
    set(value) {
      checkOpen()
      active = value
      surface?.isActive = value
    }

  /** Layout direction for standalone style content and start/end camera padding. */
  public var layoutDirection: LayoutDirection
    get() = direction
    set(value) {
      checkOpen()
      direction = value
    }

  /** Insets added to camera padding for camera moves and fitting. See [MaplibreMap]. */
  public var viewportInsets: PaddingValues
    get() = options.viewportInsets
    set(value) = update { copy(viewportInsets = value) }

  public var cameraConstraints: CameraConstraints
    get() = options.cameraConstraints
    set(value) = update { copy(cameraConstraints = value) }

  public var renderOptions: RenderOptions
    get() = options.renderOptions
    set(value) = update { copy(renderOptions = value) }

  public var interactions: MapInteractions
    get() = options.interactions
    set(value) = update { copy(interactions = value) }

  init {
    check(!state.isClosed) { "The map state is closed" }
    startComposition()
  }

  private fun startComposition() {
    val owner = MapPresentationOwnerToken()
    lateinit var clock: BroadcastFrameClock
    clock = BroadcastFrameClock {
      clockFrame = window.requestAnimationFrame { time ->
        clockFrame = null
        clock.sendFrame((time * 1_000_000).toLong())
      }
    }
    try {
      val surface =
        WebMapSurface({ density = it }, ::fail).also {
          this.surface = it
          it.isActive = isActive
        }
      composition =
        PresentationComposition(CoroutineScope(scope.coroutineContext + clock), ::post) {
          CompositionLocalProvider(
            LocalDensity provides Density(density),
            LocalLayoutDirection provides direction,
          ) {
            GlJsMapPresentation(state, owner, options, surface.element) { session, _ ->
              DisposableEffect(session) {
                surface.connect(session)
                onDispose { surface.close() }
              }
              val presentFrames = session.canPresentFrames
              SideEffect { surface.showFrames(presentFrames) }
            }
          }
        }
    } catch (error: Throwable) {
      close()
      throw error
    }
    scope.launch {
      snapshotFlow { state.isClosed }.first { it }
      post { close() }
    }
  }

  /**
   * Attaches to [container], replacing the previous binding. The caller owns the container and
   * gives it a CSS width and height. The presentation adds one child and leaves other children and
   * the container's styles unchanged. Size and device pixel ratio changes are observed.
   *
   * An empty size hides the map until layout gives it a positive size. Use an element in the
   * current document and close the binding before removing or repurposing it.
   */
  public fun attachContainer(container: HTMLElement): ContainerBinding {
    checkOpen()
    require(container.ownerDocument === document) {
      "The container must belong to the current document"
    }
    val surface = checkNotNull(surface)
    require(!surface.element.contains(container)) { "The container cannot be inside the map" }
    binding?.close()
    surface.attach(container)
    return ContainerBinding(this).also { binding = it }
  }

  /** Removes owned DOM and disposes style content. Leaves [state] open. Safe to call again. */
  override fun close() {
    if (closed) return
    closed = true
    binding = null
    try {
      composition?.close()
    } catch (error: Throwable) {
      logger?.e(error) { "Web map presentation cleanup failed" }
      if (failure == null) failure = error
    } finally {
      composition = null
      surface?.close()
      surface = null
      clockFrame?.let(window::cancelAnimationFrame)
      clockFrame = null
      scope.cancel()
    }
  }

  private fun detach(binding: ContainerBinding) {
    if (this.binding !== binding) return
    this.binding = null
    surface?.detach()
  }

  private fun update(change: MapViewOptions.() -> MapViewOptions) {
    checkOpen()
    options = options.change()
  }

  private fun checkOpen() {
    check(!closed && !state.isClosed) { "The map presentation is closed" }
  }

  private fun fail(error: Throwable) {
    if (closed || failure != null) return
    failure = error
    logger?.e(error) { "Web map presentation failed" }
    close()
  }

  /**
   * The presentation's use of one container. Closing an old binding leaves its replacement alone.
   */
  public class ContainerBinding internal constructor(private val presentation: WebMapPresentation) :
    AutoCloseable {
    /**
     * Detaches the map. Style composition and camera state survive until the presentation closes.
     */
    override fun close(): Unit = presentation.detach(this)
  }
}

private fun post(action: () -> Unit) {
  window.setTimeout(action, 0)
}
