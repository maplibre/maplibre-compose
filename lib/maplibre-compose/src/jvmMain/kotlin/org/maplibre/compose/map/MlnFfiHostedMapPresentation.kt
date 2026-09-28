package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.MlnFfiMapSurface
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.backendDiagnostic
import org.maplibre.compose.mlnffi.selectBridge
import org.maplibre.compose.util.rethrowIfFatal

/** A map backed by MapLibre Native FFI, rendered through [hostFactory]. */
@Composable
internal fun rememberMlnFfiComposeMapPresentation(
  hostFactory: MlnFfiMapHostFactory,
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
): ComposeMapPresentation? {
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
