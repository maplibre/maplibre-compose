package org.maplibre.compose.map

import androidx.compose.ui.unit.LayoutDirection
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.style.BaseStyle

/**
 * Owns the [MlnFfiMapCore] behind a [MapState]. The core is created at the first session attach,
 * because that is the first moment the density and the render backend are known, and it survives
 * detach so a re-entering composition re-attaches to the live map instead of recreating it.
 */
internal class MlnFfiMapEngine(private val state: MapState) : MapEngine {

  /** The core keeps its loaded style across detach, so the state must keep its binding too. */
  override val retainsStyleAcrossDetach: Boolean
    get() = true

  private var requestedStyle: BaseStyle? = null

  internal var core: MlnFfiMapCore? = null
    private set

  private var coreScaleFactor = 0.0
  private var coreBackend: MapRenderBackend? = null
  private var closed = false

  /** Returns the live core when [scaleFactor] and [backend] still match, or replaces it. */
  internal fun acquireCore(
    scaleFactor: Double,
    layoutDirection: LayoutDirection,
    backend: MapRenderBackend,
  ): MlnFfiMapCore {
    check(!closed) { "Cannot attach a render session to a closed map state" }
    core?.let { live ->
      if (coreScaleFactor == scaleFactor && coreBackend == backend) return live
      // The loop's scale factor is fixed per map and a renderer is built for one backend.
      live.close()
    }
    val applicationOptions = MlnFfiApplication.options
    val created =
      MlnFfiMapCore(
        callbacks = state.callbacks,
        logger = state.logger,
        scaleFactor = scaleFactor,
        layoutDirection = layoutDirection,
        cacheFile = applicationOptions.cacheFile,
        resourceProviderFactory = applicationOptions.resourceProviderFactory,
      )
    core = created
    coreScaleFactor = scaleFactor
    coreBackend = backend
    requestedStyle?.let(created::setBaseStyle)
    return created
  }

  override fun setBaseStyle(style: BaseStyle) {
    requestedStyle = style
    core?.setBaseStyle(style)
  }

  override fun close() {
    if (closed) return
    closed = true
    core?.close()
    core = null
  }
}

internal actual fun createMapEngine(state: MapState): MapEngine = MlnFfiMapEngine(state)
