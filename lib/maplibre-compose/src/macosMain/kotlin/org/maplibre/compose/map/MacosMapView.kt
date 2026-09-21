package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.maplibre.compose.mlnffi.MacosMlnFfiSurface
import org.maplibre.compose.mlnffi.MapRenderBackend

@Composable
internal actual fun mapPresentationHostIdentity(): Any =
  org.maplibre.compose.macos.LocalAppKitMapHost.current

@Composable
internal actual fun rememberComposeMapPresentation(
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
): ComposeMapPresentation? {
  val runtimeBackends = remember { loadRuntimeBackends(state.runtime.logger) }
  return rememberMlnFfiComposeMapPresentation(
    renderBackend = MapRenderBackend.METAL,
    surface = { renderer, surfaceModifier, surfaceLogger, presentFrames ->
      MacosMlnFfiSurface(
        renderer = renderer,
        runtimeBackends = runtimeBackends,
        maximumFps = options.renderOptions.maximumFps,
        modifier = surfaceModifier,
        logger = surfaceLogger,
        presentWindow = presentFrames,
      )
    },
    state = state,
    presentationOwner = presentationOwner,
    options = options,
  )
}
