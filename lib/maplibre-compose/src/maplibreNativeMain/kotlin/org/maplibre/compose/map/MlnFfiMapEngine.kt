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

  /** The live render session; the shared core makes the adapter-level attach guard blind here. */
  private var activeSession: MlnFfiMapSession? = null

  /** Creates the render session over [core], refusing a second session on the same live core. */
  internal fun createSession(core: MlnFfiMapCore, backend: MapRenderBackend): MlnFfiMapSession {
    val current = activeSession
    check(current == null || current.core !== core) {
      "MapState already has an attached MaplibreMap; one MapState shows one MaplibreMap at a time"
    }
    return MlnFfiMapSession(core, backend).also { activeSession = it }
  }

  /** Forgets [session] once its composable leaves, so the next composable may create one. */
  internal fun releaseSession(session: MlnFfiMapSession) {
    if (activeSession === session) activeSession = null
  }

  /** Returns the live core when [scaleFactor] and [backend] still match, or replaces it. */
  internal fun acquireCore(
    scaleFactor: Double,
    layoutDirection: LayoutDirection,
    backend: MapRenderBackend,
  ): MlnFfiMapCore {
    check(!closed) { "Cannot attach a render session to a closed map state" }
    core?.let { live ->
      if (coreScaleFactor == scaleFactor && coreBackend == backend) return live
      // A live session must be evicted before its core closes, or it keeps rendering a destroyed
      // map; the close is idempotent with the session composable's own later dispose.
      activeSession?.let { session ->
        session.close()
        activeSession = null
      }
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
    // The session closes before the core for the same reason acquireCore evicts before recreating.
    activeSession?.close()
    activeSession = null
    core?.close()
    core = null
  }
}

internal actual fun createMapEngine(state: MapState): MapEngine = MlnFfiMapEngine(state)
