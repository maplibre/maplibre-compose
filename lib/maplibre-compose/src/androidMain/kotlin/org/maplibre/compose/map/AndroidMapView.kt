package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import org.maplibre.compose.mlnffi.AndroidMlnFfiSurface
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.SafeStyle

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  style: BaseStyle,
  rememberedStyle: SafeStyle?,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
) {
  val runtimeBackends = remember { loadRuntimeBackends(logger) }
  MlnFfiMapView(
    renderBackend = MapRenderBackend.OPENGL,
    surface = { renderer, surfaceModifier, surfaceLogger ->
      AndroidMlnFfiSurface(
        renderer = renderer,
        runtimeBackends = runtimeBackends,
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
