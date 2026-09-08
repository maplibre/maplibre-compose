package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.maplibre.compose.interaction.internal.ClickPath
import org.maplibre.compose.interaction.internal.TapFamily
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.AndroidMlnFfiSurface
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
  captureClickPath: (TapFamily) -> ClickPath?,
  hasClickHandlers: (TapFamily) -> Boolean,
  options: MapViewOptions,
) {
  val runtimeBackends = remember { loadRuntimeBackends(logger) }
  val renderBackend =
    remember(runtimeBackends) { runtimeBackends.firstOrNull() ?: MapRenderBackend.OPENGL }
  val renderMode = options.uiOptions.renderMode
  key(renderMode, renderBackend) {
    MlnFfiMapView(
      renderBackend = renderBackend,
      surface = { renderer, surfaceModifier, surfaceLogger, presentFrames ->
        AndroidMlnFfiSurface(
          renderer = renderer,
          runtimeBackends = runtimeBackends,
          backend = renderBackend,
          renderMode = renderMode,
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
      captureClickPath = captureClickPath,
      hasClickHandlers = hasClickHandlers,
      options = options,
    )
  }
}
