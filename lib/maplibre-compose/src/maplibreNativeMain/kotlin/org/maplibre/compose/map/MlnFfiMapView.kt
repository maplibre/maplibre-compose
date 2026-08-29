package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import org.maplibre.compose.mlnffi.EnsureMlnFfiConfigured
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.MlnFfiMapRenderer
import org.maplibre.compose.mlnffi.MlnFfiMapSurface
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.backendDiagnostic
import org.maplibre.compose.mlnffi.selectBridge
import org.maplibre.compose.util.rememberAbandonable
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

/** Test tag for the color shown until the first style has loaded. */
internal const val MAP_LOAD_PLACEHOLDER_TAG = "maplibre-map-load-placeholder"

/** A map backed by MapLibre Native FFI, rendered through [hostFactory]. */
@Composable
internal fun MlnFfiMapView(
  hostFactory: MlnFfiMapHostFactory,
  modifier: Modifier,
  state: MapState,
  options: MapOptions,
) {
  val density = LocalDensity.current

  // Safe to call off the owner thread: it only inspects what the loaded library was built with.
  val runtimeBackends = remember { loadRuntimeBackends(state.logger) }
  val scaleFactor = density.density.toDouble()
  val hostSelection =
    rememberAbandonable(
      hostFactory,
      runtimeBackends,
      scaleFactor,
      onAbandoned = { (it.result as? MlnFfiMapHostResult.Created)?.host?.close() },
      create = { selectHost(runtimeBackends, hostFactory) },
    )

  MlnFfiMapView(
    renderBackend = hostSelection.backends.producer,
    surface = { renderer, surfaceModifier, surfaceLogger, presentFrames ->
      MlnFfiMapSurface(
        renderer = renderer,
        hostResult = hostSelection.result,
        modifier = surfaceModifier,
        logger = surfaceLogger,
        presentFrames = presentFrames,
      )
    },
    modifier = modifier,
    state = state,
    options = options,
  )
}

/** A map rendered by a platform surface that owns its presentation loop. */
@Composable
internal fun MlnFfiMapView(
  renderBackend: MapRenderBackend,
  surface: @Composable (MlnFfiMapRenderer, Modifier, Logger?, Boolean) -> Unit,
  modifier: Modifier,
  state: MapState,
  options: MapOptions,
) {
  EnsureMlnFfiConfigured()
  val layoutDirection = LocalLayoutDirection.current
  val density = LocalDensity.current
  val scaleFactor = density.density.toDouble()
  val logger = state.logger

  // The engine reuses a live core whose density and backend match, so re-entering the composition
  // re-attaches to the same map instead of recreating it.
  val engine = state.engine
  val resource =
    rememberAbandonable(
      renderBackend,
      scaleFactor,
      onAbandoned = { it.release() },
      create = { MlnFfiSessionResource(engine, scaleFactor, layoutDirection, renderBackend) },
    )
  val core = resource.core
  val session = resource.session

  core.callbacks = state.callbacks
  core.logger = logger

  MapSessionHost(
    resource = resource,
    state = state,
    attach = {
      // Attach deferred state before native events can report the map's default state to Compose.
      state.attachSession(core)
      core.start()
    },
  ) { focusRequester, continuation ->
    // MapLibre renders black until a style loads.
    val revealSurface = core.hasLoadedFirstStyle

    // Before the first render target attaches, gestures would project through the bootstrap 1x1
    // viewport and jump the camera.
    val inputModifier =
      if (revealSurface) {
        modifier.mapInput(session, options.gestureOptions, density, focusRequester, continuation)
      } else {
        modifier
      }

    Box {
      surface(session, inputModifier, logger, revealSurface)
      if (!revealSurface) {
        Box(
          Modifier.matchParentSize()
            .background(options.renderOptions.foregroundLoadColor)
            .testTag(MAP_LOAD_PLACEHOLDER_TAG)
        )
      }
    }
  }
}

/** The composable's claim on the engine: a core obtained and a session constructed eagerly. */
private class MlnFfiSessionResource(
  private val engine: MapEngine,
  private val scaleFactor: Double,
  layoutDirection: LayoutDirection,
  private val backend: MapRenderBackend,
) : MapSessionResource<MlnFfiMapSession> {
  val core: MlnFfiMapCore = engine.obtainCore(scaleFactor, layoutDirection, backend)

  override val session: MlnFfiMapSession = MlnFfiMapSession(core, backend)

  override fun register() {
    engine.publishCore(core, scaleFactor, backend)
    engine.registerSession(session)
  }

  override fun release() {
    session.close()
    engine.releaseSession(session)
    // A published core is retained; only an abandoned, unpublished replacement closes here.
    engine.discardCore(core)
  }
}

/** The bridge a map selected, with the outcome of creating its host. */
private class MlnFfiHostSelection(
  val backends: RenderBackendPair,
  val result: MlnFfiMapHostResult,
)

private fun selectHost(
  runtimeBackends: Set<MapRenderBackend>,
  factory: MlnFfiMapHostFactory,
): MlnFfiHostSelection {
  // The factory's first bridge stands in when nothing matches, so a failed selection still builds
  // the session the diagnostic is reported against.
  val backends = selectBridge(runtimeBackends, factory.bridges) ?: factory.bridges.first()
  val diagnostic =
    backendDiagnostic(
      runtimeBackends = runtimeBackends,
      hostBridges = factory.bridges,
      hostDescription = factory.description,
      operatingSystem = mlnFfiOperatingSystem,
      architecture = mlnFfiArchitecture,
    )
  if (diagnostic != null)
    return MlnFfiHostSelection(backends, MlnFfiMapHostResult.Failed(diagnostic))

  return MlnFfiHostSelection(
    backends,
    try {
      factory.create(backends)
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      MlnFfiMapHostResult.Failed("${factory.description} threw while creating a map host", error)
    },
  )
}

/**
 * Reports which backends the packaged MapLibre Native FFI runtime was built with. Empty rather than
 * throwing when no runtime is on the classpath; negotiation reports that as a diagnostic.
 */
internal fun loadRuntimeBackends(logger: Logger?): Set<MapRenderBackend> =
  try {
    Maplibre.loadNativeLibrary()
    Maplibre.supportedRenderBackends().mapNotNullTo(mutableSetOf()) { it.toComposeBackend() }
  } catch (error: Throwable) {
    rethrowIfFatal(error)
    logger?.e(error) { "Could not load the MapLibre Native FFI runtime" }
    emptySet()
  }

/**
 * The MapLibre Compose producer backend this FFI backend corresponds to, or null when no host
 * bridge exists for it — WebGPU today, which the FFI builds only for the browser.
 */
private fun RenderBackend.toComposeBackend(): MapRenderBackend? =
  when (this) {
    RenderBackend.METAL -> MapRenderBackend.METAL
    RenderBackend.VULKAN -> MapRenderBackend.VULKAN
    RenderBackend.OPENGL -> MapRenderBackend.OPENGL
    RenderBackend.WEBGPU -> null
  }
