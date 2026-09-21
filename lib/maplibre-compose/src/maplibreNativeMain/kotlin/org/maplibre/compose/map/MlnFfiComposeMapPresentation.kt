package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.MlnFfiMapRenderer
import org.maplibre.compose.mlnffi.MlnFfiMapSurface
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.backendDiagnostic
import org.maplibre.compose.mlnffi.selectBridge
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

/** Test tag for the color shown until the first style has loaded. */
internal const val MAP_LOAD_PLACEHOLDER_TAG = "maplibre-map-load-placeholder"

/** A map backed by MapLibre Native FFI, rendered through [hostFactory]. */
@Composable
internal fun rememberMlnFfiComposeMapPresentation(
  hostFactory: MlnFfiMapHostFactory,
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
): ComposeMapPresentation {
  val density = LocalDensity.current
  val logger = state.runtime.logger
  // Safe to call off the owner thread: it only inspects what the loaded library was built with.
  val runtimeBackends = remember { loadRuntimeBackends(logger) }
  val scaleFactor = density.density.toDouble()
  val hostSelection =
    remember(hostFactory, runtimeBackends, scaleFactor) { selectHost(runtimeBackends, hostFactory) }

  return rememberMlnFfiComposeMapPresentation(
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
    state = state,
    presentationOwner = presentationOwner,
    options = options,
  )
}

/** A map rendered by a platform surface that owns its presentation loop. */
@Composable
internal fun rememberMlnFfiComposeMapPresentation(
  renderBackend: MapRenderBackend,
  surface: @Composable (MlnFfiMapRenderer, Modifier, MapLog?, Boolean) -> Unit,
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
): ComposeMapPresentation {
  return MlnFfiMapPresentation(renderBackend, state, presentationOwner, options) { session, clicks
    ->
    ComposeMapPresentation(
      session,
      clicks,
      { state.attachmentAuthority.setEngaged(session, it) },
    ) { modifier ->
      MlnFfiMapSurfaceContent(session, options, modifier) { surfaceModifier, revealSurface ->
        surface(session, surfaceModifier, state.runtime.logger, revealSurface)
      }
    }
  }
}

/** Draws the surface and its loading placeholder. Input belongs to their common parent. */
@Composable
internal fun MlnFfiMapSurfaceContent(
  session: MlnFfiMapSession,
  options: MapViewOptions,
  modifier: Modifier,
  surface: @Composable (Modifier, Boolean) -> Unit,
) {
  val revealSurface = session.canPresentFrames
  Box(modifier) {
    surface(Modifier.fillMaxSize(), revealSurface)
    if (!revealSurface) {
      Box(
        Modifier.matchParentSize()
          .background(options.uiOptions.loadColor)
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
 * The corresponding Compose producer backend, or null if no host supports it. WebGPU is currently
 * supported only by the browser FFI runtime.
 */
private fun RenderBackend.toComposeBackend(): MapRenderBackend? =
  when (this) {
    RenderBackend.METAL -> MapRenderBackend.METAL
    RenderBackend.VULKAN -> MapRenderBackend.VULKAN
    RenderBackend.OPENGL -> MapRenderBackend.OPENGL
    RenderBackend.WEBGPU -> null
  }
