package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.maplibre.compose.mlnffi.AndroidMlnFfiSurface
import org.maplibre.compose.mlnffi.MapRenderBackend

@Composable internal actual fun mapPresentationHostIdentity(): Any = Unit

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
) {
  val logger = state.runtime.logger
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
      presentationOwner = presentationOwner,
      options = options,
    )
  }
}
