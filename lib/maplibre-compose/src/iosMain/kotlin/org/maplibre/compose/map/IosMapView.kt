package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.IosMlnFfiSurface
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.style.BaseStyle

@Composable internal actual fun mapPresentationHostIdentity(): Any = Unit

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  state: MapState,
  style: BaseStyle,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: MapLog?,
  callbacks: MapAdapter.Callbacks,
  clicks: MapInteractionTarget,
  options: MapViewOptions,
) {
  val runtimeBackends = remember { loadRuntimeBackends(logger) }
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
    style = style,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
    clicks = clicks,
    options = options,
  )
}
