package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.maplibre.compose.mlnffi.IosMlnFfiSurface
import org.maplibre.compose.mlnffi.MapRenderBackend

@Composable internal actual fun mapPresentationHostIdentity(): Any = Unit

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
) {
  val runtimeBackends = remember { loadRuntimeBackends(state.runtime.logger) }
  MlnFfiMapView(
    renderBackend = MapRenderBackend.METAL,
    surface = { renderer, surfaceModifier, surfaceLogger, presentFrames ->
      IosMlnFfiSurface(
        renderer = renderer,
        runtimeBackends = runtimeBackends,
        maximumFps = options.renderOptions.maximumFps,
        modifier = surfaceModifier,
        logger = surfaceLogger,
        presentWindow = presentFrames,
      )
    },
    modifier = modifier,
    state = state,
    presentationOwner = presentationOwner,
    options = options,
  )
}
