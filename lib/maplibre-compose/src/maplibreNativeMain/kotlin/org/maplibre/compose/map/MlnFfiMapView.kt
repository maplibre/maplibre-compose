package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.MlnFfiMapRenderer
import org.maplibre.compose.mlnffi.MlnFfiMapSurface
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.backendDiagnostic
import org.maplibre.compose.mlnffi.selectBridge
import org.maplibre.compose.style.BaseStyle
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
  style: BaseStyle,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: MapLog?,
  callbacks: MapAdapter.Callbacks,
  clicks: MapClickTarget,
  options: MapViewOptions,
) {
  val density = LocalDensity.current

  // Safe to call off the owner thread: it only inspects what the loaded library was built with.
  val runtimeBackends = remember { loadRuntimeBackends(logger) }
  val scaleFactor = density.density.toDouble()
  val hostSelection =
    remember(hostFactory, runtimeBackends, scaleFactor) { selectHost(runtimeBackends, hostFactory) }

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
    style = style,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
    clicks = clicks,
    options = options,
  )
}

/** A map rendered by a platform surface that owns its presentation loop. */
@Composable
internal fun MlnFfiMapView(
  renderBackend: MapRenderBackend,
  surface: @Composable (MlnFfiMapRenderer, Modifier, MapLog?, Boolean) -> Unit,
  modifier: Modifier,
  state: MapState,
  style: BaseStyle,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: MapLog?,
  callbacks: MapAdapter.Callbacks,
  clicks: MapClickTarget,
  options: MapViewOptions,
) {
  val applicationOptions = state.runtime.nativeRuntimeOptions
  val layoutDirection = LocalLayoutDirection.current
  val density = LocalDensity.current
  val scaleFactor = density.density.toDouble()
  val compatibility =
    remember(renderBackend, scaleFactor) {
      NativeEngineCompatibility(renderBackend = renderBackend, scaleFactor = scaleFactor)
    }
  val retainedSession = state.retainedAdapter(compatibility) as? MlnFfiMapSession

  val unpreparedSession =
    retainedSession
      ?: remember(renderBackend, scaleFactor, applicationOptions, state) {
        MlnFfiMapSession(
          lifecycleAuthority = state.lifecycle,
          callbacks = callbacks,
          logger = logger,
          renderBackend = renderBackend,
          scaleFactor = scaleFactor,
          layoutDirection = layoutDirection,
          cacheFile = applicationOptions.cacheFile,
          resourceProviderFactory = applicationOptions.resourceProviderFactory,
          resourceConfig = state.runtime.resourceConfig,
        )
      }
  val session = remember(unpreparedSession) { unpreparedSession.apply { preparePresentation() } }

  session.durableCallbacks = state.durableStyleCallbacks()
  session.callbacks = callbacks
  session.logger = logger
  session.layoutDirection = layoutDirection
  val currentUpdate = rememberUpdatedState(update)
  val currentOnReset = rememberUpdatedState(onReset)

  // Must run in the apply phase, not from a coroutine: the unload has to precede the content
  // subcomposition inserting layers, or a style switch fails anchor validation (see #269).
  SideEffect { session.setBaseStyle(style) }
  SideEffect {
    if (session.beginPresentationAttachment() && session.isPresentationPublished) {
      currentUpdate.value(session)
    }
  }
  LaunchedEffect(session) {
    try {
      session.attachPresentation()
      if (!session.isPresentationPublished) {
        currentUpdate.value(session)
        if (state.currentMapAttachment?.adapter !== session) return@LaunchedEffect
      }
      session.publishRetainedStyle()
    } catch (_: MapClosedException) {
      // A still-mounted UI on a closed map is inert.
    } catch (_: MapLeaseInvalidatedException) {
      // Detach or close won before attach finished.
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      callbacks.onStyleFailed(session, error.message)
    }
  }

  DisposableEffect(session) {
    onDispose {
      currentOnReset.value()
    }
  }

  val focusRequester = remember { FocusRequester() }
  val inputFocus =
    remember(session, state) {
      MapInputFocus { engaged -> state.setEngaged(session, engaged) }
    }
  // A press can engage the map before the attachment publishes, and a write before that is
  // dropped.
  val attached = state.currentMapAttachment?.adapter === session
  LaunchedEffect(inputFocus, attached) { if (attached) inputFocus.replay() }
  val inputEnvironment = mapInputEnvironment()
  val inputScope = rememberCoroutineScope()
  val continuation = remember(session, inputScope) { GestureContinuation(inputScope) }
  val rotaryNotchPixels = rotaryNotchPixels()

  // MapLibre renders black until a style loads.
  val revealSurface = session.canPresentFrames

  val inputModifier =
    modifier.mapInput(
      session,
      clicks,
      options.gestureOptions,
      density,
      focusRequester,
      inputFocus,
      inputEnvironment,
      continuation,
      rotaryNotchPixels,
    )

  // The indication draws over the surface and the load placeholder alike.
  Box(Modifier.indication(inputFocus.indicationInteractions, inputEnvironment.indication)) {
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
internal fun loadRuntimeBackends(logger: MapLog?): Set<MapRenderBackend> =
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
