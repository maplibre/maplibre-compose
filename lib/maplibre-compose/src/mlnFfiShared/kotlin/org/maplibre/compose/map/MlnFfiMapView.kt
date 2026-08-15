package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import co.touchlab.kermit.Logger
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.MlnFfiMapRenderer
import org.maplibre.compose.mlnffi.MlnFfiMapSurface
import org.maplibre.compose.mlnffi.backendDiagnostic
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

/** A map backed by MapLibre Native FFI, rendered through [hostFactory]. */
@Composable
internal fun MlnFfiMapView(
  hostFactory: MlnFfiMapHostFactory,
  modifier: Modifier,
  style: BaseStyle,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
) {
  val density = LocalDensity.current

  // Safe to call off the owner thread: it only inspects what the loaded library was built with.
  val runtimeBackends = remember { loadRuntimeBackends(logger) }
  val scaleFactor = density.density.toDouble()
  val hostResult =
    remember(hostFactory, runtimeBackends, scaleFactor) { createHost(runtimeBackends, hostFactory) }

  MlnFfiMapView(
    renderBackend = hostFactory.backends.producer,
    surface = { renderer, surfaceModifier, surfaceLogger ->
      MlnFfiMapSurface(
        renderer = renderer,
        hostResult = hostResult,
        modifier = surfaceModifier,
        logger = surfaceLogger,
      )
    },
    modifier = modifier,
    style = style,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
    options = options,
  )
}

/** A map rendered by a platform surface that owns its presentation loop. */
@Composable
internal fun MlnFfiMapView(
  renderBackend: MapRenderBackend,
  surface: @Composable (MlnFfiMapRenderer, Modifier, Logger?) -> Unit,
  modifier: Modifier,
  style: BaseStyle,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
) {
  val applicationOptions = MlnFfiApplication.options
  val layoutDirection = LocalLayoutDirection.current
  val density = LocalDensity.current
  val scaleFactor = density.density.toDouble()

  val session =
    remember(renderBackend, scaleFactor, applicationOptions) {
      MlnFfiMapSession(
        callbacks = callbacks,
        logger = logger,
        renderBackend = renderBackend,
        scaleFactor = scaleFactor,
        layoutDirection = layoutDirection,
        cacheFile = applicationOptions.cacheFile,
        resourceProviderFactory = applicationOptions.resourceProviderFactory,
      )
    }

  session.callbacks = callbacks
  session.logger = logger
  session.layoutDirection = layoutDirection
  val currentOnReset = rememberUpdatedState(onReset)

  // Must run in the apply phase, not from a coroutine: the unload has to precede the content
  // subcomposition inserting layers, or a style switch fails anchor validation (see #269).
  SideEffect { session.setBaseStyle(style) }

  LaunchedEffect(session, options, update) {
    // Attach deferred state before native events can report the map's default state to Compose.
    update(session)
    session.start()
  }

  DisposableEffect(session) {
    onDispose {
      session.close()
      currentOnReset.value()
    }
  }

  val focusRequester = remember { FocusRequester() }
  val inputScope = rememberCoroutineScope()
  val continuation = remember(session, inputScope) { GestureContinuation(inputScope) }

  val inputModifier =
    modifier.mapInput(session, options.gestureOptions, density, focusRequester, continuation)
  surface(session, inputModifier, logger)
}

private fun createHost(
  runtimeBackends: Set<MapRenderBackend>,
  factory: MlnFfiMapHostFactory,
): MlnFfiMapHostResult {
  val diagnostic =
    backendDiagnostic(
      runtimeBackends = runtimeBackends,
      hostBackends = factory.backends,
      hostDescription = factory.description,
      operatingSystem = mlnFfiOperatingSystem,
      architecture = mlnFfiArchitecture,
    )
  if (diagnostic != null) return MlnFfiMapHostResult.Failed(diagnostic)

  return try {
    factory.create()
  } catch (error: Throwable) {
    rethrowIfFatal(error)
    MlnFfiMapHostResult.Failed("${factory.description} threw while creating a map host", error)
  }
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
