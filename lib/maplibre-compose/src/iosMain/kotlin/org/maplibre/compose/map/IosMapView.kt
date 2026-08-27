package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.maplibre.compose.mlnffi.IosMlnFfiSurface
import org.maplibre.compose.mlnffi.MapRenderBackend

@Composable
internal actual fun ComposableMapView(state: MapState, modifier: Modifier, options: MapOptions) {
  val runtimeBackends = remember { loadRuntimeBackends(state.logger) }
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
    options = options,
  )
}
