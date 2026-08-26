package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import org.maplibre.compose.mlnffi.IosMlnFfiSurface
import org.maplibre.compose.mlnffi.MapRenderBackend

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  engine: MapEngine,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
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
    engine = engine,
    update = update,
    onReset = onReset,
    logger = logger,
    callbacks = callbacks,
    options = options,
  )
}
